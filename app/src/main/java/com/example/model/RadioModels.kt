package com.example.model

import android.net.Uri

data class SongItem(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val contentUri: Uri,
    val albumArtUri: Uri? = null,
    val isBuiltInDemo: Boolean = false
)

enum class DjInterventionPhase(val spanishLabel: String) {
    IDLE("En espera (Música al 100%)"),
    FADING_DOWN("Bajando volumen (Fade-down)"),
    SPEAKING("Locutor al aire (Ducking activo)"),
    FADING_UP("Recuperando volumen (Fade-up)")
}

data class RadioEngineState(
    val playlist: List<SongItem> = emptyList(),
    val currentSong: SongItem? = null,
    val isPlaying: Boolean = false,
    val musicPlayerStateLabel: String = "Listo",
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val userMasterVolume: Float = 0.85f,
    val originalVolumeBeforeIntervention: Float = 0.85f,
    val currentRealtimeMusicVolume: Float = 0.85f,
    val duckingTargetVolume: Float = 0.18f,
    val fadeDurationSeconds: Float = 1.4f,
    val interventionPhase: DjInterventionPhase = DjInterventionPhase.IDLE,
    val announcerDurationMs: Long = 6500L,
    val announcerPositionMs: Long = 0L,
    val announcerAudioLabel: String = "demo_locutor.wav",
    val statusBannerText: String = "Estudio listo — Seleccioná un tema o probá el locutor"
) {
    val isInterventionActive: Boolean
        get() = interventionPhase != DjInterventionPhase.IDLE

    val combinedPlaybackStatus: String
        get() {
            val musicPart = if (isPlaying) "Música: Reproduciendo" else "Música: En pausa"
            val djPart = when (interventionPhase) {
                DjInterventionPhase.IDLE -> "Locutor: Inactivo"
                DjInterventionPhase.FADING_DOWN -> "Locutor: Atenuando música"
                DjInterventionPhase.SPEAKING -> "Locutor: Hablando (demo_locutor.wav)"
                DjInterventionPhase.FADING_UP -> "Locutor: Recuperando volumen"
            }
            return "$musicPart • $djPart"
        }
}
