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
    val isBuiltInDemo: Boolean = false,
    val genre: String = "Pop / Rock Argentino"
)

enum class RadioStyle(val displayName: String) {
    POP_ARGENTINA("Radio pop argentina"),
    NOCTURNA("Radio nocturna"),
    ROCK_ARGENTINO("Rock argentino"),
    ENERGETICA("Radio energética"),
    TRANQUILA("Radio tranquila")
}

enum class InterventionType(val apiValue: String, val displayName: String) {
    PRESENTACION("presentacion", "Presentación"),
    CONTINUIDAD("continuidad", "Continuidad"),
    CAMBIO_CLIMA("cambio_clima", "Cambio de clima"),
    DATO_CURIOSO("dato_curioso", "Dato curioso"),
    MENSAJE_OYENTE("mensaje_oyente", "Mensaje de oyente")
}

enum class DjInterventionPhase(val spanishLabel: String) {
    IDLE("Listo"),
    GENERATING_SCRIPT("Generando intervención"),
    GENERATING_VOICE("Generando voz"),
    FADING_DOWN("Bajando volumen (Fade-down)"),
    SPEAKING("Locutor al aire"),
    FADING_UP("Recuperando volumen")
}

object VisibleRadioStates {
    const val READY = "Listo"
    const val PLAYING = "Reproduciendo"
    const val GENERATING_INTERVENTION = "Generando intervención"
    const val GENERATING_VOICE = "Generando voz"
    const val ANNOUNCER_ON_AIR = "Locutor al aire"
    const val RECOVERING_VOLUME = "Recuperando volumen"
    const val OFFLINE = "Sin conexión"
    const val USING_FALLBACK_AUDIO = "Usando audio de respaldo"
    const val GROQ_TEMP_ERROR = "Error temporal de Groq"
}

data class RadioEngineState(
    val playlist: List<SongItem> = emptyList(),
    val currentSong: SongItem? = null,
    val isPlaying: Boolean = false,
    val musicPlayerStateLabel: String = VisibleRadioStates.READY,
    val visibleUserStatus: String = VisibleRadioStates.READY,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val userMasterVolume: Float = 0.85f,
    val originalVolumeBeforeIntervention: Float = 0.85f,
    val currentRealtimeMusicVolume: Float = 0.85f,
    val duckingTargetVolume: Float = 0.18f,
    val announcerVoiceVolume: Float = 0.50f,
    val fadeDurationSeconds: Float = 1.4f,
    val interventionPhase: DjInterventionPhase = DjInterventionPhase.IDLE,
    val announcerDurationMs: Long = 6500L,
    val announcerPositionMs: Long = 0L,
    val announcerAudioLabel: String = "Voz Neural Masculina HD",
    val statusBannerText: String = "Estudio listo — Seleccioná un tema o probá el locutor",
    // Controles de estilo e intervención con Groq
    val djEnabled: Boolean = true,
    val selectedRadioStyle: RadioStyle = RadioStyle.POP_ARGENTINA,
    val selectedInterventionType: InterventionType = InterventionType.PRESENTACION,
    val autoInterventionEverySongs: Int = 2,
    val maxInterventionDurationSeconds: Int = 15,
    val lastGeneratedScript: String = "Buenas noches y bienvenidos a Radio Mía... Qué lindo encontrarnos acá en el aire, compartiendo la mejor música que te acompaña. Subí el volumen que ya suena lo que elegiste.",
    val lastScriptTone: String = "calido",
    val lastTextModelUsed: String = "openai/gpt-oss-120b",
    val lastTtsStatus: String = "Voz neural masculina lista (máx. 15s)",
    val lastCacheHit: Boolean = false,
    val isUsingFallbackAudio: Boolean = false,
    val backendUrl: String = "https://radio-mia.onrender.com",
    val backendConnected: Boolean = false,
    val lastTechnicalDetail: String = "Backend configurado en https://radio-mia.onrender.com"
) {
    val isInterventionActive: Boolean
        get() = interventionPhase != DjInterventionPhase.IDLE

    val combinedPlaybackStatus: String
        get() {
            val musicPart = if (isPlaying) "Música: Reproduciendo" else "Música: En pausa"
            val djPart = when (interventionPhase) {
                DjInterventionPhase.IDLE -> "Locutor: Inactivo"
                DjInterventionPhase.GENERATING_SCRIPT -> "Locutor: Generando intervención (Groq)"
                DjInterventionPhase.GENERATING_VOICE -> "Locutor: Generando voz masculina"
                DjInterventionPhase.FADING_DOWN -> "Locutor: Atenuando música"
                DjInterventionPhase.SPEAKING -> "Locutor: Al aire ($announcerAudioLabel)"
                DjInterventionPhase.FADING_UP -> "Locutor: Recuperando volumen"
            }
            return "$musicPart • $djPart"
        }
}
