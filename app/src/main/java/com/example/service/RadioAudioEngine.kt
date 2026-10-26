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
import com.example.data.LocalAudioRepository
import com.example.model.DjInterventionPhase
import com.example.model.RadioEngineState
import com.example.model.SongItem
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
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
 *  2) voicePlayer: Reproductor dedicado para "demo_locutor.wav" que se superpone sin pausar la música.
 */
class RadioAudioEngine private constructor(private val appContext: Context) {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = LocalAudioRepository(appContext)

    private val _state = MutableStateFlow(RadioEngineState())
    val state: StateFlow<RadioEngineState> = _state.asStateFlow()

    private var announcerUri: Uri? = null
    private var interventionJob: Job? = null
    private var progressPollingJob: Job? = null
    private var voiceCompletionDeferred: CompletableDeferred<Unit>? = null
    private var mediaController: MediaController? = null

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
     * Segundo reproductor exclusivo para la voz del locutor (demo_locutor.wav).
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
            _state.update {
                it.copy(
                    isPlaying = isPlaying,
                    musicPlayerStateLabel = resolvePlayerStateLabel(musicPlayer.playbackState, isPlaying)
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
            voiceCompletionDeferred?.complete(Unit)
        }
    }

    init {
        engineScope.launch {
            val (uri, sourceLabel) = repository.resolveAnnouncerAudioUri()
            announcerUri = uri
            _state.update { it.copy(announcerAudioLabel = sourceLabel) }
            prepareAnnouncerTrack(uri)
        }
        startTelemetryPolling()
        ensureServiceBound()
    }

    private fun prepareAnnouncerTrack(uri: Uri) {
        val item = MediaItem.Builder()
            .setMediaId("demo_locutor")
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
            _state.update { it.copy(statusBannerText = "Música en pausa: ${current.title}") }
        } else {
            if (musicPlayer.playbackState == Player.STATE_IDLE || musicPlayer.playbackState == Player.STATE_ENDED) {
                musicPlayer.prepare()
            }
            musicPlayer.play()
            _state.update { it.copy(statusBannerText = "Reproduciendo: ${current.title}") }
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

        if (phase == DjInterventionPhase.IDLE) {
            musicPlayer.volume = clamped
            _state.update {
                it.copy(
                    userMasterVolume = clamped,
                    originalVolumeBeforeIntervention = clamped,
                    currentRealtimeMusicVolume = clamped
                )
            }
        } else {
            // Durante una intervención activa:
            // Actualizamos userMasterVolume y originalVolumeBeforeIntervention para que el fade-up
            // recupere hasta este nuevo volumen maestro elegido por el usuario.
            // Si está en SPEAKING y el nuevo volumen maestro es incluso menor al volumen de ducking,
            // ajustamos también el volumen instantáneo para no sonar más fuerte que el nuevo tope.
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
     * Acción principal para "Probar locutor" e "Intervención del DJ".
     * - Si NO hay una intervención activa:
     *     1. Asegura que la canción continúe reproduciéndose (nunca la pausa; si estaba detenida, la inicia).
     *     2. Baja gradualmente el volumen de la música hasta el nivel configurable (FADING_DOWN).
     *     3. Reproduce demo_locutor.wav en el segundo reproductor (SPEAKING).
     *     4. Al finalizar demo_locutor.wav, sube gradualmente el volumen al nivel maestro vigente (FADING_UP -> IDLE).
     * - Si YA hay una intervención activa:
     *     1. Cancela limpiamente la corrutina de intervención actual.
     *     2. Detiene de inmediato demo_locutor.wav y lo rebobina.
     *     3. Recupera gradualmente el volumen de la canción hacia el volumen maestro vigente sin dejar recursos activos.
     */
    fun triggerOrToggleAnnouncer() {
        ensureServiceBound()

        if (_state.value.isInterventionActive) {
            cancelActiveInterventionAndRestoreVolume()
            return
        }

        // Asegurar que la canción esté sonando y continúe reproduciéndose durante toda la intervención
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

        interventionJob?.cancel()
        interventionJob = engineScope.launch {
            try {
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

                // 1. Fading Down gradual de la música (sin pausarla jamás)
                rampMusicVolume(
                    fromVolume = musicPlayer.volume,
                    toVolumeProvider = { minOf(_state.value.duckingTargetVolume, _state.value.userMasterVolume) },
                    durationMs = fadeDownMs
                )

                // 2. Reproducir demo_locutor.wav mientras la canción sigue reproduciéndose a bajo volumen
                val uri = announcerUri ?: repository.resolveAnnouncerAudioUri().first.also { announcerUri = it }
                val completion = CompletableDeferred<Unit>()
                voiceCompletionDeferred = completion

                if (voicePlayer.mediaItemCount == 0) {
                    prepareAnnouncerTrack(uri)
                } else {
                    voicePlayer.seekTo(0L)
                    if (voicePlayer.playbackState == Player.STATE_IDLE || voicePlayer.playbackState == Player.STATE_ENDED) {
                        voicePlayer.prepare()
                    }
                }

                voicePlayer.volume = 1.0f
                voicePlayer.play()

                _state.update {
                    it.copy(
                        interventionPhase = DjInterventionPhase.SPEAKING,
                        announcerPositionMs = 0L,
                        statusBannerText = "LOCUTOR AL AIRE — Reproduciendo demo_locutor.wav"
                    )
                }

                // Esperar a que termine la voz del locutor
                completion.await()

                // 3. Cuando termina la voz, subir gradualmente el volumen al volumen maestro actual
                // (respetando cualquier cambio de volumen maestro realizado durante la intervención)
                val fadeUpMs = (_state.value.fadeDurationSeconds * 1000L).toLong().coerceAtLeast(250L)
                _state.update {
                    it.copy(
                        interventionPhase = DjInterventionPhase.FADING_UP,
                        statusBannerText = "Locución finalizada — Recuperando volumen al ${(_state.value.userMasterVolume * 100).toInt()}%…"
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
                        currentRealtimeMusicVolume = finalVolume,
                        announcerPositionMs = 0L,
                        statusBannerText = "Volumen recuperado al ${(finalVolume * 100).toInt()}%"
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
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

        // Detener inmediatamente la voz del locutor y rebobinar a 0
        if (voicePlayer.isPlaying) {
            voicePlayer.pause()
        }
        voicePlayer.stop()
        voicePlayer.seekTo(0L)
        announcerUri?.let { prepareAnnouncerTrack(it) }

        interventionJob = engineScope.launch {
            val currentVol = musicPlayer.volume
            val restoreDurationMs = ((_state.value.fadeDurationSeconds * 650L).toLong()).coerceIn(250L, 1200L)

            _state.update {
                it.copy(
                    interventionPhase = DjInterventionPhase.FADING_UP,
                    announcerPositionMs = 0L,
                    statusBannerText = "Intervención cancelada — Recuperando volumen de música…"
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
            Player.STATE_IDLE -> "En espera"
            Player.STATE_BUFFERING -> "Cargando buffer…"
            Player.STATE_READY -> if (isPlaying) "Reproduciendo" else "En pausa"
            Player.STATE_ENDED -> "Finalizado"
            else -> "Listo"
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
