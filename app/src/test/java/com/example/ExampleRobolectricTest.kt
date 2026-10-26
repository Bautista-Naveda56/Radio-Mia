package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.LocalAudioRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `verifica nombre de app y recursos de audio demo_locutor y pistas libres de derechos`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Radio Mía", context.getString(R.string.app_name))
        assertEquals("RADIO MÍA — AL AIRE", context.getString(R.string.header_on_air))

        val repository = LocalAudioRepository(context)
        val builtInSongs = repository.getBuiltInRoyaltyFreeSongs()
        assertEquals(3, builtInSongs.size)

        val (announcerUri, label) = repository.resolveAnnouncerAudioUri()
        assertTrue(announcerUri.toString().isNotBlank())
        assertTrue(label.contains("demo_locutor.wav"))
    }
}
