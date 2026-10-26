package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.model.SongItem
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.AmberPrimaryContainer
import com.example.ui.theme.DividerSubtle
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.OnAirCoral
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

@Composable
fun SongLibrarySection(
    songs: List<SongItem>,
    currentSong: SongItem?,
    isPlaying: Boolean,
    hasAudioPermission: Boolean,
    localSongsCount: Int,
    demoSongsCount: Int,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onSelectSong: (SongItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Tarjeta de estado de permiso MediaStore
        if (!hasAudioPermission) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("permission_rationale_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AmberPrimaryContainer.copy(alpha = 0.65f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = "Permiso de audio",
                            tint = AmberPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Acceso a canciones del teléfono (MediaStore)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "Concedé el permiso de lectura de audio para cargar los temas descargados en tu dispositivo. Mientras tanto, ya podés reproducir las pistas instrumentales libres de derechos incluidas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Button(
                        onClick = onRequestPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = AmberPrimary),
                        modifier = Modifier.testTag("grant_audio_permission_button")
                    ) {
                        Text(
                            text = "Permitir acceso a música local",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            // Resumen de MediaStore + Botón actualizar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "BIBLIOTECA MUSICAL",
                        style = MaterialTheme.typography.labelLarge,
                        color = AmberPrimary
                    )
                    Text(
                        text = "$localSongsCount en MediaStore • $demoSongsCount pistas demo libres de derechos",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                IconButton(
                    onClick = onRefreshLibrary,
                    modifier = Modifier.testTag("refresh_library_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Actualizar biblioteca MediaStore",
                        tint = AmberPrimary
                    )
                }
            }
        }

        // Buscador de canciones
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("song_search_input"),
            placeholder = {
                Text(
                    text = "Buscar por título, artista o álbum…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Buscar",
                    tint = AmberPrimary
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "Limpiar búsqueda",
                            tint = TextSecondary
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AmberPrimary,
                unfocusedBorderColor = DividerSubtle,
                focusedContainerColor = StudioSurface,
                unfocusedContainerColor = StudioSurface
            )
        )

        if (hasAudioPermission && localSongsCount == 0) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = StudioSurfaceElevated
            ) {
                Text(
                    text = "No se encontraron archivos MP3/WAV externos en MediaStore de este dispositivo. Podés usar las pistas instrumentales de demostración incluidas abajo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("song_list"),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(songs, key = { it.id }) { song ->
                val isSelected = currentSong?.id == song.id
                SongRowCard(
                    song = song,
                    isSelected = isSelected,
                    isPlaying = isSelected && isPlaying,
                    onClick = { onSelectSong(song) }
                )
            }
        }
    }
}

@Composable
private fun SongRowCard(
    song: SongItem,
    isSelected: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val borderColor = if (isSelected) AmberPrimary else DividerSubtle
    val bgColor = if (isSelected) StudioSurfaceElevated else StudioSurface

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(if (isSelected) 1.5.dp else 1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .testTag("song_item_${song.id}"),
        color = bgColor,
        tonalElevation = if (isSelected) 6.dp else 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Portada de la canción (MediaStore AlbumArt o portada vinilo de Radio Mía)
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(StudioSurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                if (song.albumArtUri != null) {
                    AsyncImage(
                        model = song.albumArtUri,
                        contentDescription = "Portada de ${song.title}",
                        contentScale = ContentScale.Crop,
                        error = painterResource(id = R.drawable.img_vinyl_artwork_1792807437211),
                        placeholder = painterResource(id = R.drawable.img_vinyl_artwork_1792807437211),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.img_vinyl_artwork_1792807437211),
                        contentDescription = "Portada de ${song.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Título, Artista y Álbum
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSelected) AmberPrimary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (song.isBuiltInDemo) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = TelemetryCyan.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = "RESPALDO",
                                style = MaterialTheme.typography.labelSmall,
                                color = TelemetryCyan,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Album,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = song.album,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Duración e indicador de reproducción
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = formatDurationMmSs(song.durationMs),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = if (isSelected) AmberPrimary else TextSecondary
                )

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPlaying) OnAirCoral else AmberPrimary.copy(alpha = 0.18f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Equalizer else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Sonando" else "Reproducir ${song.title}",
                        tint = if (isPlaying) MaterialTheme.colorScheme.onSecondary else AmberPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
