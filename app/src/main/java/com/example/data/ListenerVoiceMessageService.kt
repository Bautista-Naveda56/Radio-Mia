package com.example.data

import android.util.Base64
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Interfaz preparada para futura etapa de Speech-to-Text (Sección 4.3 del documento de continuidad):
 * permite transcribir mensajes de voz de oyentes a través del backend seguro de Radio Mía
 * sin exponer GROQ_API_KEY en el cliente Android.
 */
interface ListenerVoiceMessageTranscriber {
    suspend fun transcribeVoiceNote(
        audioFile: File,
        backendBaseUrl: String,
        language: String = "es"
    ): Result<String>
}

class BackendListenerVoiceMessageService : ListenerVoiceMessageTranscriber {
    override suspend fun transcribeVoiceNote(
        audioFile: File,
        backendBaseUrl: String,
        language: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(audioFile.exists() && audioFile.length() > 0L) {
                "El archivo de voz del oyente no existe o está vacío."
            }
            val base64Audio = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
            val payload = JSONObject().apply {
                put("audioBase64", base64Audio)
                put("language", language)
            }

            val cleanUrl = backendBaseUrl.trim().trimEnd('/')
            val conn = (URL("$cleanUrl/api/stt").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("Error STT HTTP ${conn.responseCode}")
            }

            val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            JSONObject(body).optString("text", "").trim()
        }
    }
}
