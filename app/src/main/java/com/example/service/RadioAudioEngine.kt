package com.example.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.BackendInterventionOutcome
import com.example.data.LocalAudioRepository
import com.example.data.RadioBackendClient
import com.example.model.DjInterventionPhase
import com.example.model.InterventionType
import com.example.model.RadioEngineState
import com.example.model.RadioStyle
import com.example.model.SongItem
import com.example.model.VisibleRadioStates
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Motor central de audio de Radio Mía.
 * Coordina dos instancias de ExoPlayer:
 *  1) musicPlayer: Reproductor principal de canciones locales / respaldo expuesto en MediaSessionService.
 *  2) voicePlayer: Reproductor dedicado para la voz del locutor (Groq TTS o demo_locutor.wav)
 *     que se superpone sin pausar jamás la música.
 */
class RadioAudioEngine private constructor(private val appContext: Context) {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = LocalAudioRepository(appContext)
    val backendClient = RadioBackendClient(appContext)

    private val _state = MutableStateFlow(RadioEngineState())
    val state: StateFlow<RadioEngineState> = _state.asStateFlow()

    private var fallbackAnnouncerUri: Uri? = null
    private var activeVoiceUri: Uri? = null
    private var interventionJob: Job? = null
    private var progressPollingJob: Job? = null
    private var voiceCompletionDeferred: CompletableDeferred<Unit>? = null
    private var mediaController: MediaController? = null
    private var songsPlayedSinceLastIntervention: Int = 0

    val musicPlayer: ExoPlayer by lazy {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        ExoPlayer.Builder(appContext)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ALL
                volume = _state.value.userMasterVolume
                addListener(musicPlayerListener)
            }
    }

    /**
     * Segundo reproductor exclusivo para la voz del locutor (voz Groq o demo_locutor.wav).
     * Importante: handleAudioFocus = false para que Android NO pause ni silencie a musicPlayer.
     */
    val voicePlayer: ExoPlayer by lazy {
        val voiceAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        ExoPlayer.Builder(appContext)
            .setAudioAttributes(voiceAttributes, false)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                volume = 1.0f
                addListener(voicePlayerListener)
            }
    }

    private val musicPlayerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { current ->
                val nextVisible = if (current.isInterventionActive) {
                    current.visibleUserStatus
                } else if (isPlaying) {
                    VisibleRadioStates.PLAYING
                } else {
                    VisibleRadioStates.READY
                }
                current.copy(
                    isPlaying = isPlaying,
                    musicPlayerStateLabel = resolvePlayerStateLabel(musicPlayer.playbackState, isPlaying),
                    visibleUserStatus = nextVisible
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val dur = musicPlayer.duration.takeIf { it > 0 } ?: _state.value.currentSong?.durationMs ?: 0L
            _state.update {
                it.copy(
                    durationMs = dur,
                    isPlaying = musicPlayer.isPlaying,
                    musicPlayerStateLabel = resolvePlayerStateLabel(playbackState, musicPlayer.isPlaying)
                )
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val mediaId = mediaItem?.mediaId?.toLongOrNull()
            val matchedSong = _state.value.playlist.firstOrNull { it.id == mediaId }
            if (matchedSong != null) {
                _state.update {
                    it.copy(
                        currentSong = matchedSong,
                        durationMs = matchedSong.durationMs,
                        currentPositionMs = 0L,
                        statusBannerText = "Sonando: ${matchedSong.title}"
                    )
                }

                // Si el DJ automático está habilitado y no hay otra intervención activa,
                // dispara una presentación al cumplir la frecuencia configurada.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && _state.value.djEnabled) {
                    songsPlayedSinceLastIntervention += 1
                    val freq = _state.value.autoInterventionEverySongs.coerceAtLeast(1)
                    if (songsPlayedSinceLastIntervention >= freq && !_state.value.isInterventionActive) {
                        songsPlayedSinceLastIntervention = 0
                        triggerOrToggleAnnouncer(useGroqBackend = true)
                    }
                }
            }
        }
    }

    private val voicePlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val voiceDur = voicePlayer.duration.takeIf { it > 0 }
            if (voiceDur != null) {
                _state.update { it.copy(announcerDurationMs = voiceDur) }
            }
            if (playbackState == Player.STATE_ENDED) {
                voiceCompletionDeferred?.complete(Unit)
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // Si el audio generado no se puede reproducir, liberar inmediatamente la espera
            // para no dejar nunca la música atenuada.
            voiceCompletionDeferred?.complete(Unit)
        }
    }

    init {
        engineScope.launch {
            val (uri, sourceLabel) = repository.resolveAnnouncerAudioUri()
            fallbackAnnouncerUri = uri
            activeVoiceUri = uri
            _state.update { it.copy(announcerAudioLabel = sourceLabel) }
            prepareAnnouncerTrack(uri, "demo_locutor")
            refreshBackendStatus()
        }
        startTelemetryPolling()
        ensureServiceBound()
    }

    private fun prepareAnnouncerTrack(uri: Uri, mediaId: String = "announcer_voice") {
        activeVoiceUri = uri
        val item = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Intervención Locutor")
                    .setArtist("Radio Mía")
                    .build()
            )
            .build()
        voicePlayer.setMediaItem(item)
        voicePlayer.prepare()
    }

    fun refreshBackendStatus() {
        engineScope.launch {
            val (connected, detail) = backendClient.checkBackendHealth(_state.value.backendUrl)
            _state.update {
                it.copy(
                    backendConnected = connected,
                    lastTechnicalDetail = detail
                )
            }
        }
    }

    fun setBackendUrl(newUrl: String) {
        val cleaned = newUrl.trim().ifEmpty { "http://10.0.2.2:3000" }
        _state.update {
            it.copy(
                backendUrl = cleaned,
                lastTechnicalDetail = "URL de backend actualizada a $cleaned"
            )
        }
        refreshBackendStatus()
    }

    fun setDjEnabled(enabled: Boolean) {
        _state.update {
            it.copy(
                djEnabled = enabled,
                statusBannerText = if (enabled) {
                    "DJ Virtual activado (${it.selectedRadioStyle.displayName})"
                } else {
                    "DJ automático desactivado — Modo solo música"
                }
            )
        }
    }

    fun setRadioStyle(style: RadioStyle) {
        _state.update {
            it.copy(
                selectedRadioStyle = style,
                statusBannerText = "Estilo sintonizado: ${style.displayName}"
            )
        }
    }

    fun setInterventionType(type: InterventionType) {
        _state.update {
            it.copy(
                selectedInterventionType = type,
                statusBannerText = "Modo de locución: ${type.displayName}"
            )
        }
    }

    fun setAutoInterventionFrequency(everySongs: Int) {
        val clamped = everySongs.coerceIn(1, 5)
        _state.update { it.copy(autoInterventionEverySongs = clamped) }
    }

    /**
     * Inicia y enlaza RadioPlaybackService (MediaSessionService) para garantizar
     * reproducción continua en segundo plano y con la pantalla bloqueada.
     */
    fun ensureServiceBound() {
        if (mediaController != null) return
        runCatching {
            val serviceIntent = Intent(appContext, RadioPlaybackService::class.java)
            appContext.startService(serviceIntent)

            val sessionToken = SessionToken(
                appContext,
                ComponentName(appContext, RadioPlaybackService::class.java)
            )
            val controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()
            controllerFuture.addListener(
                {
                    runCatching {
                        mediaController = controllerFuture.get()
                    }
                },
                ContextCompat.getMainExecutor(appContext)
            )
        }
    }

    fun setPlaylist(songs: List<SongItem>, autoSelectFirst: Boolean = true) {
        val currentId = _state.value.currentSong?.id
        val preservedSong = songs.firstOrNull { it.id == currentId }
            ?: if (autoSelectFirst) songs.firstOrNull() else null

        _state.update {
            it.copy(
                playlist = songs,
                currentSong = preservedSong,
                durationMs = preservedSong?.durationMs ?: 0L
            )
        }

        if (songs.isNotEmpty() && musicPlayer.mediaItemCount == 0) {
            loadPlaylistIntoPlayer(songs, startIndex = 0, playWhenReady = false)
        }
    }

    private fun loadPlaylistIntoPlayer(songs: List<SongItem>, startIndex: Int, playWhenReady: Boolean) {
        val mediaItems = songs.map { song ->
            MediaItem.Builder()
                .setMediaId(song.id.toString())
                .setUri(song.contentUri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(song.albumArtUri)
                        .build()
                )
                .build()
        }
        musicPlayer.setMediaItems(mediaItems, startIndex.coerceIn(0, max(0, mediaItems.size - 1)), 0L)
        musicPlayer.prepare()
        musicPlayer.playWhenReady = playWhenReady
    }

    fun playSong(song: SongItem) {
        ensureServiceBound()
        val songs = _state.value.playlist.ifEmpty { listOf(song) }
        val index = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)

        loadPlaylistIntoPlayer(songs, startIndex = index, playWhenReady = true)
        musicPlayer.play()

        _state.update {
            it.copy(
                currentSong = song,
                isPlaying = true,
                visibleUserStatus = if (it.isInterventionActive) it.visibleUserStatus else VisibleRadioStates.PLAYING,
                durationMs = song.durationMs,
                currentPositionMs = 0L,
                statusBannerText = "Reproduciendo: ${song.title}"
            )
        }
    }

    fun togglePlayPause() {
        ensureServiceBound()
        val current = _state.value.currentSong ?: _state.value.playlist.firstOrNull() ?: return
        if (musicPlayer.mediaItemCount == 0) {
            playSong(current)
            return
        }
        if (musicPlayer.isPlaying) {
            musicPlayer.pause()
            _state.update {
                it.copy(
                    visibleUserStatus = if (it.isInterventionActive) it.visibleUserStatus else VisibleRadioStates.READY,
                    statusBannerText = "Música en pausa: ${current.title}"
                )
            }
        } else {
            if (musicPlayer.playbackState == Player.STATE_IDLE || musicPlayer.playbackState == Player.STATE_ENDED) {
                musicPlayer.prepare()
            }
            musicPlayer.play()
            _state.update {
                it.copy(
                    visibleUserStatus = if (it.isInterventionActive) it.visibleUserStatus else VisibleRadioStates.PLAYING,
                    statusBannerText = "Reproduciendo: ${current.title}"
                )
            }
        }
    }

    fun skipToNext() {
        val songs = _state.value.playlist
        if (songs.isEmpty()) return
        val currentId = _state.value.currentSong?.id
        val currentIndex = songs.indexOfFirst { it.id == currentId }
        val nextIndex = if (currentIndex in 0 until songs.lastIndex) currentIndex + 1 else 0
        playSong(songs[nextIndex])
    }

    fun skipToPrevious() {
        val songs = _state.value.playlist
        if (songs.isEmpty()) return
        if (musicPlayer.currentPosition > 3500L) {
            musicPlayer.seekTo(0L)
            return
        }
        val currentId = _state.value.currentSong?.id
        val currentIndex = songs.indexOfFirst { it.id == currentId }
        val prevIndex = if (currentIndex > 0) currentIndex - 1 else songs.lastIndex
        playSong(songs[prevIndex])
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        musicPlayer.seekTo(target)
        _state.update { it.copy(currentPositionMs = target) }
    }

    /**
     * Ajusta el volumen maestro de la música.
     * Si hay una intervención activa, actualiza el volumen maestro objetivo para que al finalizar
     * el fade-up se respete el nuevo nivel elegido por el usuario.
     */
    fun setMasterVolume(newVolume: Float) {
        val clamped = newVolume.coerceIn(0f, 1f)
        val phase = _state.value.interventionPhase

        if (phase == DjInterventionPhase.IDLE ||
            phase == DjInterventionPhase.GENERATING_SCRIPT ||
            phase == DjInterventionPhase.GENERATING_VOICE
        ) {
            musicPlayer.volume = clamped
            _state.update {
                it.copy(
                    userMasterVolume = clamped,
                    originalVolumeBeforeIntervention = clamped,
                    currentRealtimeMusicVolume = clamped
                )
            }
        } else {
            val effectiveDuckTarget = minOf(_state.value.duckingTargetVolume, clamped)
            if (phase == DjInterventionPhase.SPEAKING) {
                musicPlayer.volume = effectiveDuckTarget
            }
            _state.update {
                it.copy(
                    userMasterVolume = clamped,
                    originalVolumeBeforeIntervention = clamped,
                    currentRealtimeMusicVolume = musicPlayer.volume
                )
            }
        }
    }

    fun setDuckingTargetVolume(targetVolume: Float) {
        val clamped = targetVolume.coerceIn(0.05f, 0.70f)
        _state.update { it.copy(duckingTargetVolume = clamped) }
        if (_state.value.interventionPhase == DjInterventionPhase.SPEAKING) {
            val effectiveDuck = minOf(clamped, _state.value.userMasterVolume)
            musicPlayer.volume = effectiveDuck
            _state.update { it.copy(currentRealtimeMusicVolume = effectiveDuck) }
        }
    }

    fun setFadeDurationSeconds(seconds: Float) {
        val clamped = seconds.coerceIn(0.3f, 3.5f)
        _state.update { it.copy(fadeDurationSeconds = clamped) }
    }

    /**
     * Acción principal para "Intervención del DJ" y "Probar locutor".
     * - Si YA hay una intervención activa:
     *     Cancela de inmediato la intervención, detiene el reproductor de voz y recupera el volumen.
     * - Si NO hay una intervención activa:
     *     1. Asegura que la canción esté sonando y continúe sin pausarse mientras se consulta a Groq.
     *     2. Solicita al backend seguro de Radio Mía el guion y voz en español (con caché y timeout).
     *     3. Si no hay red o Groq falla, usa demo_locutor.wav como respaldo.
     *     4. Hace fade-down gradual de la canción.
     *     5. Reproduce la voz en voicePlayer sobre la canción en curso.
     *     6. Al terminar la voz, hace fade-up gradual hasta userMasterVolume.
     */
    fun triggerOrToggleAnnouncer(useGroqBackend: Boolean = true) {
        ensureServiceBound()

        if (_state.value.isInterventionActive) {
            cancelActiveInterventionAndRestoreVolume()
            return
        }

        val currentOrFirstSong = _state.value.currentSong ?: _state.value.playlist.firstOrNull()
        if (currentOrFirstSong != null) {
            if (musicPlayer.mediaItemCount == 0) {
                playSong(currentOrFirstSong)
            } else if (!musicPlayer.isPlaying) {
                if (musicPlayer.playbackState == Player.STATE_IDLE || musicPlayer.playbackState == Player.STATE_ENDED) {
                    musicPlayer.prepare()
                }
                musicPlayer.play()
            }
        }

        val songForMetadata = currentOrFirstSong ?: SongItem(
            id = -101L,
            title = "Medianoche en Corrientes (Instrumental)",
            artist = "Ensamble Nocturno Radio Mía",
            album = "Sesiones de Estudio",
            durationMs = 26_000L,
            contentUri = Uri.EMPTY,
            isBuiltInDemo = true
        )

        interventionJob?.cancel()
        interventionJob = engineScope.launch {
            try {
                val fallbackUri = fallbackAnnouncerUri
                    ?: repository.resolveAnnouncerAudioUri().first.also { fallbackAnnouncerUri = it }

                var chosenVoiceUri: Uri = fallbackUri
                var chosenAudioLabel = "demo_locutor.wav"
                var usingFallback = true

                if (useGroqBackend) {
                    // Paso 2 y 3: Solicitar intervención al backend mientras la música sigue sonando al 100%
                    _state.update {
                        it.copy(
                            originalVolumeBeforeIntervention = it.userMasterVolume,
                            interventionPhase = DjInterventionPhase.GENERATING_SCRIPT,
                            visibleUserStatus = VisibleRadioStates.GENERATING_INTERVENTION,
                            statusBannerText = "${VisibleRadioStates.GENERATING_INTERVENTION} para ${songForMetadata.title}…"
                        )
                    }

                    val outcome = backendClient.requestIntervention(
                        song = songForMetadata,
                        radioStyle = _state.value.selectedRadioStyle,
                        interventionType = _state.value.selectedInterventionType,
                        maxDurationSeconds = _state.value.maxInterventionDurationSeconds,
                        primaryBaseUrl = _state.value.backendUrl
                    )

                    when (outcome) {
                        is BackendInterventionOutcome.SuccessWithGroqAudio -> {
                            chosenVoiceUri = outcome.wavFileUri
                            chosenAudioLabel = outcome.wavFileLabel
                            usingFallback = false
                            _state.update {
                                it.copy(
                                    interventionPhase = DjInterventionPhase.GENERATING_VOICE,
                                    visibleUserStatus = VisibleRadioStates.GENERATING_VOICE,
                                    lastGeneratedScript = outcome.scriptText,
                                    lastScriptTone = outcome.tone,
                                    lastTextModelUsed = outcome.textModelUsed,
                                    lastTtsStatus = "Voz en español: ${outcome.ttsVoiceUsed} (${outcome.ttsModelUsed})",
                                    lastCacheHit = outcome.cached,
                                    isUsingFallbackAudio = false,
                                    backendConnected = true,
                                    announcerAudioLabel = chosenAudioLabel,
                                    lastTechnicalDetail = "Guion y voz generados con Groq (${outcome.textModelUsed} + ${outcome.ttsModelUsed})",
                                    statusBannerText = VisibleRadioStates.GENERATING_VOICE
                                )
                            }
                        }

                        is BackendInterventionOutcome.SuccessScriptWithFallbackAudio -> {
                            chosenVoiceUri = fallbackUri
                            chosenAudioLabel = "demo_locutor.wav"
                            usingFallback = true
                            _state.update {
                                it.copy(
                                    interventionPhase = DjInterventionPhase.GENERATING_VOICE,
                                    visibleUserStatus = VisibleRadioStates.USING_FALLBACK_AUDIO,
                                    lastGeneratedScript = outcome.scriptText,
                                    lastScriptTone = outcome.tone,
                                    lastTextModelUsed = outcome.textModelUsed,
                                    lastTtsStatus = outcome.ttsStatusMessage,
                                    lastCacheHit = outcome.cached,
                                    isUsingFallbackAudio = true,
                                    backendConnected = true,
                                    announcerAudioLabel = chosenAudioLabel,
                                    lastTechnicalDetail = outcome.technicalReason,
                                    statusBannerText = "${VisibleRadioStates.USING_FALLBACK_AUDIO} • Guion Groq listo"
                                )
                            }
                        }

                        is BackendInterventionOutcome.OfflineOrUnreachable -> {
                            chosenVoiceUri = fallbackUri
                            chosenAudioLabel = "demo_locutor.wav"
                            usingFallback = true
                            _state.update {
                                it.copy(
                                    visibleUserStatus = VisibleRadioStates.OFFLINE,
                                    lastTtsStatus = "${VisibleRadioStates.OFFLINE} — ${VisibleRadioStates.USING_FALLBACK_AUDIO}",
                                    isUsingFallbackAudio = true,
                                    backendConnected = false,
                                    announcerAudioLabel = chosenAudioLabel,
                                    lastTechnicalDetail = outcome.technicalDetail,
                                    statusBannerText = "${VisibleRadioStates.OFFLINE} • ${VisibleRadioStates.USING_FALLBACK_AUDIO}"
                                )
                            }
                        }

                        is BackendInterventionOutcome.GroqError -> {
                            chosenVoiceUri = fallbackUri
                            chosenAudioLabel = "demo_locutor.wav"
                            usingFallback = true
                            _state.update {
                                it.copy(
                                    visibleUserStatus = VisibleRadioStates.GROQ_TEMP_ERROR,
                                    lastTtsStatus = "${VisibleRadioStates.GROQ_TEMP_ERROR} — ${VisibleRadioStates.USING_FALLBACK_AUDIO}",
                                    isUsingFallbackAudio = true,
                                    announcerAudioLabel = chosenAudioLabel,
                                    lastTechnicalDetail = outcome.technicalDetail,
                                    statusBannerText = "${VisibleRadioStates.GROQ_TEMP_ERROR} • ${VisibleRadioStates.USING_FALLBACK_AUDIO}"
                                )
                            }
                        }
                    }
                } else {
                    chosenVoiceUri = fallbackUri
                    chosenAudioLabel = "demo_locutor.wav"
                    usingFallback = true
                    _state.update {
                        it.copy(
                            visibleUserStatus = VisibleRadioStates.USING_FALLBACK_AUDIO,
                            isUsingFallbackAudio = true,
                            announcerAudioLabel = chosenAudioLabel
                        )
                    }
                }

                // Paso 5.1: Fading Down gradual de la música (sin pausarla jamás)
                val startMasterVolume = _state.value.userMasterVolume
                val targetDuckVolume = minOf(_state.value.duckingTargetVolume, startMasterVolume)
                val fadeDownMs = (_state.value.fadeDurationSeconds * 1000L).toLong().coerceAtLeast(250L)

                _state.update {
                    it.copy(
                        originalVolumeBeforeIntervention = startMasterVolume,
                        interventionPhase = DjInterventionPhase.FADING_DOWN,
                        statusBannerText = "Atenuando música al ${(targetDuckVolume * 100).toInt()}% para el locutor…"
                    )
                }

                rampMusicVolume(
                    fromVolume = musicPlayer.volume,
                    toVolumeProvider = { minOf(_state.value.duckingTargetVolume, _state.value.userMasterVolume) },
                    durationMs = fadeDownMs
                )

                // Paso 5.2: Reproducir la voz del locutor en el segundo reproductor
                val completion = CompletableDeferred<Unit>()
                voiceCompletionDeferred = completion

                prepareAnnouncerTrack(
                    uri = chosenVoiceUri,
                    mediaId = if (usingFallback) "demo_locutor" else "groq_tts_voice"
                )
                voicePlayer.seekTo(0L)
                voicePlayer.volume = 1.0f
                voicePlayer.play()

                _state.update {
                    it.copy(
                        interventionPhase = DjInterventionPhase.SPEAKING,
                        visibleUserStatus = if (usingFallback && !it.backendConnected) {
                            VisibleRadioStates.USING_FALLBACK_AUDIO
                        } else {
                            VisibleRadioStates.ANNOUNCER_ON_AIR
                        },
                        announcerPositionMs = 0L,
                        statusBannerText = "${VisibleRadioStates.ANNOUNCER_ON_AIR} — Reproduciendo $chosenAudioLabel"
                    )
                }

                // Esperar el final de la voz del locutor
                completion.await()

                // Paso 5.3: Subir gradualmente el volumen al volumen maestro vigente
                val fadeUpMs = (_state.value.fadeDurationSeconds * 1000L).toLong().coerceAtLeast(250L)
                _state.update {
                    it.copy(
                        interventionPhase = DjInterventionPhase.FADING_UP,
                        visibleUserStatus = VisibleRadioStates.RECOVERING_VOLUME,
                        statusBannerText = "${VisibleRadioStates.RECOVERING_VOLUME} al ${(_state.value.userMasterVolume * 100).toInt()}%…"
                    )
                }

                rampMusicVolume(
                    fromVolume = musicPlayer.volume,
                    toVolumeProvider = { _state.value.userMasterVolume },
                    durationMs = fadeUpMs
                )

                val finalVolume = _state.value.userMasterVolume
                musicPlayer.volume = finalVolume
                _state.update {
                    it.copy(
                        interventionPhase = DjInterventionPhase.IDLE,
                        visibleUserStatus = if (musicPlayer.isPlaying) VisibleRadioStates.PLAYING else VisibleRadioStates.READY,
                        currentRealtimeMusicVolume = finalVolume,
                        announcerPositionMs = 0L,
                        statusBannerText = "Volumen recuperado al ${(finalVolume * 100).toInt()}%"
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unexpected: Exception) {
                // Regla crítica Sección 12: Nunca dejar la música permanentemente atenuada por una excepción
                val safeVol = _state.value.userMasterVolume
                musicPlayer.volume = safeVol
                _state.update {
                    it.copy(
                        interventionPhase = DjInterventionPhase.IDLE,
                        visibleUserStatus = VisibleRadioStates.GROQ_TEMP_ERROR,
                        currentRealtimeMusicVolume = safeVol,
                        announcerPositionMs = 0L,
                        lastTechnicalDetail = "Excepción recuperada: ${unexpected.message}",
                        statusBannerText = "${VisibleRadioStates.GROQ_TEMP_ERROR} — Volumen restaurado al ${(safeVol * 100).toInt()}%"
                    )
                }
            }
        }
    }

    /**
     * Cancela limpiamente la intervención activa, detiene la voz de inmediato y recupera
     * gradualmente el volumen de la música hacia userMasterVolume sin dejar corrutinas ni reproductores colgados.
     */
    fun cancelActiveInterventionAndRestoreVolume() {
        interventionJob?.cancel()
        voiceCompletionDeferred?.cancel()
        voiceCompletionDeferred = null

        if (voicePlayer.isPlaying) {
            voicePlayer.pause()
        }
        voicePlayer.stop()
        voicePlayer.seekTo(0L)
        fallbackAnnouncerUri?.let { prepareAnnouncerTrack(it, "demo_locutor") }

        interventionJob = engineScope.launch {
            val currentVol = musicPlayer.volume
            val restoreDurationMs = ((_state.value.fadeDurationSeconds * 650L).toLong()).coerceIn(250L, 1200L)

            _state.update {
                it.copy(
                    interventionPhase = DjInterventionPhase.FADING_UP,
                    visibleUserStatus = VisibleRadioStates.RECOVERING_VOLUME,
                    announcerPositionMs = 0L,
                    statusBannerText = "Intervención cancelada — ${VisibleRadioStates.RECOVERING_VOLUME}…"
                )
            }

            rampMusicVolume(
                fromVolume = currentVol,
                toVolumeProvider = { _state.value.userMasterVolume },
                durationMs = restoreDurationMs
            )

            val targetVol = _state.value.userMasterVolume
            musicPlayer.volume = targetVol
            _state.update {
                it.copy(
                    interventionPhase = DjInterventionPhase.IDLE,
                    visibleUserStatus = if (musicPlayer.isPlaying) VisibleRadioStates.PLAYING else VisibleRadioStates.READY,
                    currentRealtimeMusicVolume = targetVol,
                    announcerPositionMs = 0L,
                    statusBannerText = "Intervención cancelada — Volumen en ${(targetVol * 100).toInt()}%"
                )
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (_state.value.interventionPhase == DjInterventionPhase.IDLE) {
                    interventionJob = null
                }
            }
        }
    }

    private suspend fun rampMusicVolume(
        fromVolume: Float,
        toVolumeProvider: () -> Float,
        durationMs: Long
    ) {
        val stepIntervalMs = 25L
        val totalSteps = (durationMs / stepIntervalMs).toInt().coerceAtLeast(1)

        for (step in 1..totalSteps) {
            val targetVol = toVolumeProvider().coerceIn(0f, 1f)
            val fraction = step.toFloat() / totalSteps.toFloat()
            val interpolated = (fromVolume + (targetVol - fromVolume) * fraction).coerceIn(0f, 1f)
            musicPlayer.volume = interpolated
            _state.update {
                it.copy(currentRealtimeMusicVolume = interpolated)
            }
            if (abs(interpolated - targetVol) < 0.002f && step == totalSteps) break
            delay(stepIntervalMs)
        }

        val finalTarget = toVolumeProvider().coerceIn(0f, 1f)
        musicPlayer.volume = finalTarget
        _state.update { it.copy(currentRealtimeMusicVolume = finalTarget) }
    }

    private fun startTelemetryPolling() {
        progressPollingJob?.cancel()
        progressPollingJob = engineScope.launch {
            while (isActive) {
                val musicPos = musicPlayer.currentPosition.coerceAtLeast(0L)
                val musicDur = musicPlayer.duration.takeIf { it > 0 }
                    ?: _state.value.currentSong?.durationMs
                    ?: 0L
                val voicePos = if (_state.value.interventionPhase == DjInterventionPhase.SPEAKING) {
                    voicePlayer.currentPosition.coerceAtLeast(0L)
                } else {
                    0L
                }
                val voiceDur = voicePlayer.duration.takeIf { it > 0 } ?: _state.value.announcerDurationMs

                _state.update {
                    it.copy(
                        currentPositionMs = musicPos,
                        durationMs = musicDur,
                        currentRealtimeMusicVolume = musicPlayer.volume,
                        announcerPositionMs = voicePos,
                        announcerDurationMs = voiceDur,
                        isPlaying = musicPlayer.isPlaying
                    )
                }
                delay(100L)
            }
        }
    }

    private fun resolvePlayerStateLabel(playbackState: Int, isPlaying: Boolean): String {
        return when (playbackState) {
            Player.STATE_IDLE -> VisibleRadioStates.READY
            Player.STATE_BUFFERING -> "Cargando buffer…"
            Player.STATE_READY -> if (isPlaying) VisibleRadioStates.PLAYING else "En pausa"
            Player.STATE_ENDED -> "Finalizado"
            else -> VisibleRadioStates.READY
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: RadioAudioEngine? = null

        fun getInstance(context: Context): RadioAudioEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RadioAudioEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
