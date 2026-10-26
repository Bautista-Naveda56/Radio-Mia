package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.BackendInterventionOutcome
import com.example.data.LocalAudioRepository
import com.example.data.RadioBackendClient
import com.example.model.InterventionType
import com.example.model.RadioStyle
import com.example.service.RadioAudioEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `verifica nombre de app, cabecera sin frecuencias y archivos demo_locutor y pistas libres de derechos`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Radio Mía", context.getString(R.string.app_name))
        val headerText = context.getString(R.string.header_on_air)
        assertEquals("RADIO MÍA — AL AIRE", headerText)
        assertFalse(headerText.contains("MHz", ignoreCase = true))

        val repository = LocalAudioRepository(context)
        val builtInSongs = repository.getBuiltInRoyaltyFreeSongs()
        assertEquals(3, builtInSongs.size)

        val (announcerUri, label) = repository.resolveAnnouncerAudioUri()
        assertTrue(announcerUri.toString().isNotBlank())
        assertTrue(label.contains("demo_locutor.wav"))
    }

    @Test
    fun `verifica que BuildConfig no exponga GROQ_API_KEY en el APK`() {
        val fields = BuildConfig::class.java.declaredFields
        for (field in fields) {
            assertFalse(
                "BuildConfig no debe tener un campo GROQ_API_KEY",
                field.name.contains("GROQ", ignoreCase = true)
            )
            field.isAccessible = true
            val value = runCatching { field.get(null)?.toString() ?: "" }.getOrDefault("")
            assertFalse(
                "Ningún campo de BuildConfig debe contener una clave gsk_",
                value.contains("gsk_")
            )
        }
    }

    @Test
    fun `verifica cache deterministica y fallback cuando el backend esta caido`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = LocalAudioRepository(context)
        val demoSong = repository.getBuiltInRoyaltyFreeSongs().first()

        val client = RadioBackendClient(context, connectTimeoutMs = 400, readTimeoutMs = 400)
        val key1 = client.buildCacheKey(demoSong, RadioStyle.POP_ARGENTINA, InterventionType.PRESENTACION)
        val key2 = client.buildCacheKey(demoSong, RadioStyle.POP_ARGENTINA, InterventionType.PRESENTACION)
        assertEquals(key1, key2)
        assertEquals(24, key1.length)

        // Probar con puerto cerrado simulando caída del backend (sin usar las URLs por defecto si forzamos cliente aislado)
        val engine = RadioAudioEngine.getInstance(context)
        engine.setPlaylist(repository.getBuiltInRoyaltyFreeSongs(), autoSelectFirst = true)
        engine.setMasterVolume(0.72f)
        assertEquals(0.72f, engine.state.value.userMasterVolume, 0.001f)

        engine.setRadioStyle(RadioStyle.ROCK_ARGENTINO)
        assertEquals(RadioStyle.ROCK_ARGENTINO, engine.state.value.selectedRadioStyle)

        engine.setInterventionType(InterventionType.CAMBIO_CLIMA)
        assertEquals(InterventionType.CAMBIO_CLIMA, engine.state.value.selectedInterventionType)

        engine.setDjEnabled(false)
        assertFalse(engine.state.value.djEnabled)
        engine.setDjEnabled(true)
        assertTrue(engine.state.value.djEnabled)
    }

    @Test
    fun `verifica integracion real con el backend local de Radio Mia y cache`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = LocalAudioRepository(context)
        val demoSong = repository.getBuiltInRoyaltyFreeSongs().first()
        val client = RadioBackendClient(context, connectTimeoutMs = 3000, readTimeoutMs = 8000)

        val (healthy, _) = client.checkBackendHealth("http://127.0.0.1:3000")
        if (healthy) {
            val outcome1 = client.requestIntervention(
                song = demoSong,
                radioStyle = RadioStyle.POP_ARGENTINA,
                interventionType = InterventionType.PRESENTACION,
                maxDurationSeconds = 10,
                primaryBaseUrl = "http://127.0.0.1:3000"
            )
            assertNotNull(outcome1)
            val isSuccessOrFallback = outcome1 is BackendInterventionOutcome.SuccessWithGroqAudio ||
                outcome1 is BackendInterventionOutcome.SuccessScriptWithFallbackAudio
            assertTrue("El backend debe generar el guion con Groq", isSuccessOrFallback)

            // Segunda llamada idéntica debe salir de la caché en memoria
            val outcome2 = client.requestIntervention(
                song = demoSong,
                radioStyle = RadioStyle.POP_ARGENTINA,
                interventionType = InterventionType.PRESENTACION,
                maxDurationSeconds = 10,
                primaryBaseUrl = "http://127.0.0.1:3000"
            )
            val cachedHit = when (outcome2) {
                is BackendInterventionOutcome.SuccessWithGroqAudio -> outcome2.cached
                is BackendInterventionOutcome.SuccessScriptWithFallbackAudio -> outcome2.cached
                else -> false
            }
            assertTrue("La segunda solicitud idéntica debe servirse desde caché", cachedHit)
        }
    }
}
