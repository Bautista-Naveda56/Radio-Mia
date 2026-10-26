package com.example.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Base64
import com.example.model.InterventionType
import com.example.model.RadioStyle
import com.example.model.SongItem
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class InterventionMetadataPayload(
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val durationSeconds: Int,
    val radioStyle: String,
    val interventionType: String,
    val maxDurationSeconds: Int
)

sealed class BackendInterventionOutcome {
    data class SuccessWithGroqAudio(
        val cacheKey: String,
        val cached: Boolean,
        val scriptText: String,
        val tone: String,
        val estimatedDurationSeconds: Int,
        val textModelUsed: String,
        val ttsModelUsed: String,
        val ttsVoiceUsed: String,
        val wavFileUri: Uri,
        val wavFileLabel: String
    ) : BackendInterventionOutcome()

    data class SuccessScriptWithFallbackAudio(
        val cacheKey: String,
        val cached: Boolean,
        val scriptText: String,
        val tone: String,
        val estimatedDurationSeconds: Int,
        val textModelUsed: String,
        val ttsStatusMessage: String,
        val technicalReason: String
    ) : BackendInterventionOutcome()

    data class OfflineOrUnreachable(
        val userStatus: String,
        val technicalDetail: String
    ) : BackendInterventionOutcome()

    data class GroqError(
        val userStatus: String,
        val technicalDetail: String
    ) : BackendInterventionOutcome()
}

/**
 * Cliente HTTP seguro hacia el backend de Radio Mía.
 * - Envía ÚNICAMENTE metadatos de la canción (jamás archivos de audio ni claves).
 * - No contiene ni recibe GROQ_API_KEY en ningún momento.
 * - Incluye caché en memoria y disco para evitar regenerar la misma intervención.
 */
class RadioBackendClient(
    private val context: Context,
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 15000
) {
    private val memoryCache = LinkedHashMap<String, BackendInterventionOutcome>(32, 0.75f, true)

    fun isNetworkAvailable(): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching true
            val network = cm.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    fun buildCacheKey(
        song: SongItem,
        radioStyle: RadioStyle,
        interventionType: InterventionType,
        promptVersion: String = "v2.0-male-extended-verified"
    ): String {
        val raw = listOf(
            song.title.trim().lowercase(),
            song.artist.trim().lowercase(),
            song.album.trim().lowercase(),
            radioStyle.displayName.trim().lowercase(),
            interventionType.apiValue.trim().lowercase(),
            promptVersion
        ).joinToString("|")

        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    suspend fun checkBackendHealth(primaryBaseUrl: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val urlsToTry = candidateBaseUrls(primaryBaseUrl)
        for (base in urlsToTry) {
            runCatching {
                val conn = (URL("$base/api/status").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 2000
                    readTimeout = 2500
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val model = json.optString("activeTextModel", "Groq")
                    val groqOk = json.optBoolean("groqConfigured", false)
                    return@withContext true to "Conectado ($base • Modelo: $model • Clave en backend: ${if (groqOk) "Presente" else "Ausente"})"
                }
            }
        }
        false to "Backend inaccesible en $primaryBaseUrl — Modo respaldo local activo"
    }

    suspend fun requestIntervention(
        song: SongItem,
        radioStyle: RadioStyle,
        interventionType: InterventionType,
        maxDurationSeconds: Int,
        primaryBaseUrl: String
    ): BackendInterventionOutcome = withContext(Dispatchers.IO) {
        val cacheKey = buildCacheKey(song, radioStyle, interventionType)

        synchronized(memoryCache) {
            val cached = memoryCache[cacheKey]
            if (cached != null) {
                return@withContext when (cached) {
                    is BackendInterventionOutcome.SuccessWithGroqAudio -> cached.copy(cached = true)
                    is BackendInterventionOutcome.SuccessScriptWithFallbackAudio -> cached.copy(cached = true)
                    else -> cached
                }
            }
        }

        val cleanTitle = song.title
            .replace(Regex("\\.(mp3|wav|flac|m4a|ogg)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(official\\s*video|video\\s*oficial|lyrics|audio\\s*oficial|320kbps)\\b", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { song.title }

        val cleanArtist = if (song.artist.equals("Artista Local", ignoreCase = true) ||
            song.artist.equals("<unknown>", ignoreCase = true)
        ) {
            "No especificado"
        } else {
            song.artist.take(200)
        }

        val cleanAlbum = if (song.album.equals("Biblioteca del Teléfono", ignoreCase = true) ||
            song.album.equals("Biblioteca Local", ignoreCase = true) ||
            song.album.contains("Sesiones de Estudio", ignoreCase = true) ||
            song.album.equals("Download", ignoreCase = true)
        ) {
            "No especificado"
        } else {
            song.album.take(200)
        }

        val payloadJson = JSONObject().apply {
            put("title", cleanTitle.take(200))
            put("artist", cleanArtist)
            put("album", cleanAlbum)
            put("genre", song.genre.take(120))
            put("durationSeconds", (song.durationMs / 1000L).toInt().coerceAtLeast(1))
            put("radioStyle", radioStyle.displayName)
            put("interventionType", interventionType.apiValue)
            put("maxDurationSeconds", maxDurationSeconds.coerceIn(8, 35))
        }

        val baseUrls = candidateBaseUrls(primaryBaseUrl)
        var lastExceptionMessage = "No se pudo conectar al backend de Radio Mía"
        var receivedHttpResponse = false
        var lastHttpStatus = 0
        var lastHttpErrorBody = ""

        for (baseUrl in baseUrls) {
            try {
                val url = URL("$baseUrl/api/intervention")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payloadJson.toString())
                    writer.flush()
                }

                val status = conn.responseCode
                receivedHttpResponse = true
                lastHttpStatus = status

                val responseText = if (status in 200..299) {
                    BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                } else {
                    conn.errorStream?.let { errStream ->
                        BufferedReader(InputStreamReader(errStream, Charsets.UTF_8)).use { it.readText() }
                    } ?: ""
                }

                if (status !in 200..299) {
                    lastHttpErrorBody = responseText
                    break
                }

                val json = JSONObject(responseText)
                val scriptText = json.optString("text", "").trim()
                if (scriptText.isEmpty()) {
                    return@withContext BackendInterventionOutcome.GroqError(
                        userStatus = "Error temporal de Groq",
                        technicalDetail = "El backend devolvió un guion vacío. Se usa demo_locutor.wav."
                    )
                }

                val tone = json.optString("tone", "calido")
                val estimatedSeconds = json.optInt("estimatedDurationSeconds", 8).coerceIn(4, 15)
                val textModelUsed = json.optString("textModelUsed", "Groq LLM")
                val ttsModelUsed = json.optString("ttsModelUsed", "Groq TTS")
                val ttsVoiceUsed = json.optString("ttsVoiceUsed", "diana")
                val ttsStatusMessage = json.optString("ttsStatusMessage", "Usando demo_locutor.wav")
                val audioAvailable = json.optBoolean("audioAvailable", false)
                val audioBase64 = if (json.isNull("audioBase64")) "" else json.optString("audioBase64", "")
                val serverCacheKey = json.optString("cacheKey", cacheKey)
                val wasCachedOnServer = json.optBoolean("cached", false)

                if (audioAvailable && audioBase64.isNotBlank()) {
                    val wavFile = decodeAndValidateWavFile(serverCacheKey, audioBase64)
                    if (wavFile != null) {
                        val outcome = BackendInterventionOutcome.SuccessWithGroqAudio(
                            cacheKey = serverCacheKey,
                            cached = wasCachedOnServer,
                            scriptText = scriptText,
                            tone = tone,
                            estimatedDurationSeconds = estimatedSeconds,
                            textModelUsed = textModelUsed,
                            ttsModelUsed = ttsModelUsed,
                            ttsVoiceUsed = ttsVoiceUsed,
                            wavFileUri = Uri.fromFile(wavFile),
                            wavFileLabel = "Groq TTS ($ttsVoiceUsed)"
                        )
                        putInCache(cacheKey, outcome)
                        return@withContext outcome
                    }
                }

                val fallbackOutcome = BackendInterventionOutcome.SuccessScriptWithFallbackAudio(
                    cacheKey = serverCacheKey,
                    cached = wasCachedOnServer,
                    scriptText = scriptText,
                    tone = tone,
                    estimatedDurationSeconds = estimatedSeconds,
                    textModelUsed = textModelUsed,
                    ttsStatusMessage = ttsStatusMessage,
                    technicalReason = "Guion generado con $textModelUsed ($baseUrl). $ttsStatusMessage"
                )
                putInCache(cacheKey, fallbackOutcome)
                return@withContext fallbackOutcome
            } catch (e: Exception) {
                lastExceptionMessage = "${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}"
            }
        }

        if (receivedHttpResponse) {
            val friendlyMsg = runCatching {
                JSONObject(lastHttpErrorBody).optString("message", "HTTP $lastHttpStatus")
            }.getOrDefault("HTTP $lastHttpStatus")
            return@withContext BackendInterventionOutcome.GroqError(
                userStatus = "Error temporal de Groq",
                technicalDetail = "Backend respondió HTTP $lastHttpStatus: $friendlyMsg. Se usa demo_locutor.wav."
            )
        }

        BackendInterventionOutcome.OfflineOrUnreachable(
            userStatus = "Sin conexión",
            technicalDetail = "Backend no disponible ($lastExceptionMessage). Usando demo_locutor.wav."
        )
    }

    private fun candidateBaseUrls(primaryBaseUrl: String): List<String> {
        val cleaned = primaryBaseUrl.trim().trimEnd('/')
        val defaults = listOf(
            cleaned,
            "https://radio-mia.onrender.com",
            "http://10.0.2.2:3000",
            "http://127.0.0.1:3000",
            "http://localhost:3000"
        )
        return defaults.filter { it.isNotBlank() }.distinct()
    }

    private fun decodeAndValidateWavFile(cacheKey: String, base64Audio: String): File? {
        return runCatching {
            val bytes = Base64.decode(base64Audio, Base64.DEFAULT)
            if (bytes.size <= 44) return null
            val header = String(bytes, 0, 4, Charsets.US_ASCII)
            if (header != "RIFF") return null

            val dir = File(context.cacheDir, "groq_announcer_audio").apply { mkdirs() }
            val file = File(dir, "locutor_${cacheKey.take(16)}.wav")
            file.writeBytes(bytes)
            if (file.exists() && file.length() > 44L) file else null
        }.getOrNull()
    }

    private fun putInCache(key: String, outcome: BackendInterventionOutcome) {
        synchronized(memoryCache) {
            if (memoryCache.size >= 40) {
                val eldest = memoryCache.keys.firstOrNull()
                if (eldest != null) memoryCache.remove(eldest)
            }
            memoryCache[key] = outcome
        }
    }

    fun clearCache() {
        synchronized(memoryCache) {
            memoryCache.clear()
        }
    }
}
