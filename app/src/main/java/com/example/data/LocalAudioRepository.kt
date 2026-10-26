package com.example.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.R
import com.example.model.SongItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalAudioRepository(private val context: Context) {

    /**
     * Garantiza que demo_locutor.wav exista también como archivo físico local en filesDir
     * y devuelve el Uri listo para reproducir con ExoPlayer.
     * Si el usuario colocó un demo_locutor.wav en almacenamiento local (MediaStore), lo prioriza.
     */
    suspend fun resolveAnnouncerAudioUri(): Pair<Uri, String> = withContext(Dispatchers.IO) {
        // 1. Verificar si existe un archivo llamado demo_locutor.wav en MediaStore
        val mediaStoreUri = findDemoLocutorInMediaStore()
        if (mediaStoreUri != null) {
            return@withContext mediaStoreUri to "demo_locutor.wav (Almacenamiento del teléfono)"
        }

        // 2. Copiar el recurso verificado res/raw/demo_locutor.wav al almacenamiento interno de la app
        // para garantizar que exista un archivo real "demo_locutor.wav" accesible por File/Uri.
        val localFile = File(context.filesDir, "demo_locutor.wav")
        if (!localFile.exists() || localFile.length() == 0L) {
            runCatching {
                context.resources.openRawResource(R.raw.demo_locutor).use { input ->
                    localFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

        if (localFile.exists() && localFile.length() > 0L) {
            Uri.fromFile(localFile) to "demo_locutor.wav (Incluido en proyecto)"
        } else {
            val rawUri = Uri.parse("android.resource://${context.packageName}/${R.raw.demo_locutor}")
            rawUri to "demo_locutor.wav (Recurso raw)"
        }
    }

    private fun findDemoLocutorInMediaStore(): Uri? {
        return runCatching {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME
            )
            val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%demo_locutor.wav%")
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val id = cursor.getLong(idCol)
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    /**
     * Lee todas las canciones descargadas en el dispositivo usando MediaStore.Audio.Media.
     */
    suspend fun queryLocalSongsFromMediaStore(): List<SongItem> = withContext(Dispatchers.IO) {
        val localSongs = mutableListOf<SongItem>()
        runCatching {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DISPLAY_NAME
            )

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 1000"
            val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

            context.contentResolver.query(
                collection,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

                val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(displayNameCol) ?: ""
                    if (displayName.equals("demo_locutor.wav", ignoreCase = true)) {
                        continue
                    }

                    val id = cursor.getLong(idCol)
                    val rawTitle = cursor.getString(titleCol)?.trim().orEmpty()
                    val rawArtist = cursor.getString(artistCol)?.trim().orEmpty()
                    val rawAlbum = cursor.getString(albumCol)?.trim().orEmpty()
                    val durationMs = cursor.getLong(durationCol).coerceAtLeast(1000L)
                    val albumId = cursor.getLong(albumIdCol)

                    val title = if (rawTitle.isNotEmpty() && rawTitle != "<unknown>") {
                        rawTitle
                    } else {
                        displayName.substringBeforeLast('.').ifEmpty { "Canción Local #$id" }
                    }

                    val artist = if (rawArtist.isNotEmpty() && rawArtist != "<unknown>") {
                        rawArtist
                    } else {
                        "Artista Local"
                    }

                    val album = if (rawAlbum.isNotEmpty() && rawAlbum != "<unknown>") {
                        rawAlbum
                    } else {
                        "Biblioteca del Teléfono"
                    }

                    val contentUri = ContentUris.withAppendedId(collection, id)
                    val albumArtUri = if (albumId > 0) {
                        ContentUris.withAppendedId(albumArtBaseUri, albumId)
                    } else {
                        null
                    }

                    localSongs.add(
                        SongItem(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = durationMs,
                            contentUri = contentUri,
                            albumArtUri = albumArtUri,
                            isBuiltInDemo = false
                        )
                    )
                }
            }
        }
        localSongs
    }

    /**
     * Pistas instrumentales originales 100% libres de derechos empaquetadas en res/raw
     * para garantizar que el emulador de Google AI Studio y cualquier teléfono sin MP3
     * puedan probar de inmediato la reproducción en segundo plano y el ducking del locutor.
     */
    fun getBuiltInRoyaltyFreeSongs(): List<SongItem> {
        val pkg = context.packageName
        return listOf(
            SongItem(
                id = -101L,
                title = "Medianoche en Corrientes (Instrumental)",
                artist = "Ensamble Nocturno Radio Mía",
                album = "Sesiones de Estudio (Libre de Derechos)",
                durationMs = 26_000L,
                contentUri = Uri.parse("android.resource://$pkg/${R.raw.pista_nocturna_baires}"),
                albumArtUri = null,
                isBuiltInDemo = true
            ),
            SongItem(
                id = -102L,
                title = "Pulso de Cabina FM (Instrumental)",
                artist = "Sintetizadores del Río",
                album = "Sesiones de Estudio (Libre de Derechos)",
                durationMs = 24_000L,
                contentUri = Uri.parse("android.resource://$pkg/${R.raw.pista_pulso_urbano}"),
                albumArtUri = null,
                isBuiltInDemo = true
            ),
            SongItem(
                id = -103L,
                title = "Luces de la Costanera (Instrumental)",
                artist = "Acústico Sur",
                album = "Sesiones de Estudio (Libre de Derechos)",
                durationMs = 28_000L,
                contentUri = Uri.parse("android.resource://$pkg/${R.raw.pista_luces_del_rio}"),
                albumArtUri = null,
                isBuiltInDemo = true
            )
        )
    }
}
