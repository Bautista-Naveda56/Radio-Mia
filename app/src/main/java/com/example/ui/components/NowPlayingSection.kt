package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.model.DjInterventionPhase
import com.example.model.RadioEngineState
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.DividerSubtle
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.OnAirCoral
import com.example.ui.theme.OnAirCoralContainer
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun NowPlayingSection(
    engineState: RadioEngineState,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onMasterVolumeChange: (Float) -> Unit,
    onDuckingVolumeChange: (Float) -> Unit,
    onFadeDurationChange: (Float) -> Unit,
    onTriggerAnnouncer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentSong = engineState.currentSong
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl_spin")
    val vinylRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val ringGlowColor by animateColorAsState(
        targetValue = if (engineState.isInterventionActive) OnAirCoral else AmberPrimary,
        animationSpec = tween(300),
        label = "ring_glow"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Portada de la canción actual con aro luminoso reactivo al estado de locución
        Box(
            modifier = Modifier
                .size(210.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            ringGlowColor.copy(alpha = 0.32f),
                            StudioSurfaceElevated
                        )
                    )
                )
                .border(2.dp, ringGlowColor.copy(alpha = 0.75f), RoundedCornerShape(28.dp))
                .padding(12.dp)
                .testTag("player_album_cover"),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .graphicsLayer {
                        rotationZ = if (engineState.isPlaying) vinylRotation else 0f
                    }
            ) {
                if (currentSong?.albumArtUri != null) {
                    AsyncImage(
                        model = currentSong.albumArtUri,
                        contentDescription = "Portada de ${currentSong.title}",
                        contentScale = ContentScale.Crop,
                        error = painterResource(id = R.drawable.img_vinyl_artwork_1792807437211),
                        placeholder = painterResource(id = R.drawable.img_vinyl_artwork_1792807437211),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.img_vinyl_artwork_1792807437211),
                        contentDescription = "Portada de vinilo Radio Mía",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Etiqueta flotante cuando el locutor está hablando sobre la canción
            if (engineState.isInterventionActive) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = OnAirCoral.copy(alpha = 0.92f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RecordVoiceOver,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = when (engineState.interventionPhase) {
                                DjInterventionPhase.FADING_DOWN -> "BAJANDO MÚSICA…"
                                DjInterventionPhase.SPEAKING -> "LOCUTOR AL AIRE"
                                DjInterventionPhase.FADING_UP -> "SUBIENDO MÚSICA…"
                                DjInterventionPhase.IDLE -> ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Título, Artista y Álbum
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = currentSong?.title ?: "Seleccioná una canción",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("player_song_title")
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = currentSong?.artist ?: "Radio Mía",
                style = MaterialTheme.typography.titleMedium,
                color = AmberPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("player_song_artist")
            )
            if (currentSong != null) {
                Text(
                    text = currentSong.album,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Barra de progreso de la canción + Tiempos
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = StudioSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                val totalDuration = engineState.durationMs.coerceAtLeast(1L)
                val currentPos = engineState.currentPositionMs.coerceIn(0L, totalDuration)

                Slider(
                    value = currentPos.toFloat(),
                    onValueChange = { onSeekTo(it.toLong()) },
                    valueRange = 0f..totalDuration.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = AmberPrimary,
                        activeTrackColor = AmberPrimary,
                        inactiveTrackColor = DividerSubtle
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("player_progress_slider")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDurationMmSs(currentPos),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = TextSecondary
                    )
                    Text(
                        text = engineState.statusBannerText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (engineState.isInterventionActive) OnAirCoral else AmberPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = formatDurationMmSs(totalDuration),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = TextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Controles de transporte: Canción anterior, Reproducir/Pausar, Canción siguiente
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconButton(
                        onClick = onPrevious,
                        modifier = Modifier
                            .size(52.dp)
                            .testTag("btn_previous_song"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = StudioSurfaceElevated,
                            contentColor = TextPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "Canción anterior",
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(24.dp))

                    FilledIconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .size(68.dp)
                            .testTag("btn_play_pause"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = AmberPrimary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (engineState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (engineState.isPlaying) "Pausar canción" else "Reproducir canción",
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(24.dp))

                    FilledIconButton(
                        onClick = onNext,
                        modifier = Modifier
                            .size(52.dp)
                            .testTag("btn_next_song"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = StudioSurfaceElevated,
                            contentColor = TextPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "Canción siguiente",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }

        // Control de Volumen Maestro de la Música
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = StudioSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.VolumeUp,
                            contentDescription = null,
                            tint = AmberPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Control de volumen maestro",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                    }
                    Text(
                        text = "${(engineState.userMasterVolume * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = AmberPrimary
                    )
                }

                Slider(
                    value = engineState.userMasterVolume,
                    onValueChange = onMasterVolumeChange,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = AmberPrimary,
                        activeTrackColor = AmberPrimary,
                        inactiveTrackColor = DividerSubtle
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("slider_master_volume")
                )

                Text(
                    text = if (engineState.isInterventionActive) {
                        "Intervención activa: al finalizar el locutor, la música subirá a este nuevo nivel (${(engineState.userMasterVolume * 100).toInt()}%)."
                    } else {
                        "Volumen instantáneo en reproducción: ${(engineState.currentRealtimeMusicVolume * 100).toInt()}%"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }

        // Cabina del Locutor: Botón "Intervención del DJ", Botón "Probar locutor" y Sliders de Ducking/Fade
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.5.dp,
                    color = if (engineState.isInterventionActive) OnAirCoral else DividerSubtle,
                    shape = RoundedCornerShape(20.dp)
                ),
            shape = RoundedCornerShape(20.dp),
            color = if (engineState.isInterventionActive) {
                OnAirCoralContainer.copy(alpha = 0.42f)
            } else {
                StudioSurface
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = null,
                            tint = OnAirCoral,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "CABINA DEL LOCUTOR VIRTUAL",
                                style = MaterialTheme.typography.labelLarge,
                                color = OnAirCoral
                            )
                            Text(
                                text = "Archivo local: demo_locutor.wav",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (engineState.isInterventionActive) OnAirCoral else StudioSurfaceElevated
                    ) {
                        Text(
                            text = if (engineState.isInterventionActive) "ACTIVO" else "LISTO",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Botones principales requeridos: "Intervención del DJ" y "Probar locutor"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onTriggerAnnouncer,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("btn_dj_intervention"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (engineState.isInterventionActive) AmberPrimary else OnAirCoral,
                            contentColor = if (engineState.isInterventionActive) MaterialTheme.colorScheme.onPrimary else TextPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (engineState.isInterventionActive) Icons.Filled.StopCircle else Icons.Filled.RecordVoiceOver,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (engineState.isInterventionActive) "Cancelar DJ" else "Intervención del DJ",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = onTriggerAnnouncer,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("btn_test_announcer"),
                        shape = RoundedCornerShape(14.dp),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                            brush = SolidColor(if (engineState.isInterventionActive) OnAirCoral else AmberPrimary)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (engineState.isInterventionActive) OnAirCoral else AmberPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (engineState.isInterventionActive) Icons.Filled.StopCircle else Icons.Filled.GraphicEq,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Probar locutor",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Barra de progreso de demo_locutor.wav cuando está hablando
                if (engineState.isInterventionActive) {
                    val voiceDur = engineState.announcerDurationMs.coerceAtLeast(1L)
                    val voicePos = engineState.announcerPositionMs.coerceIn(0L, voiceDur)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = engineState.interventionPhase.spanishLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = OnAirCoral
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%.1fs / %.1fs",
                                    voicePos / 1000.0,
                                    voiceDur / 1000.0
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = TextPrimary
                            )
                        }
                        LinearProgressIndicator(
                            progress = { voicePos.toFloat() / voiceDur.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = OnAirCoral,
                            trackColor = DividerSubtle
                        )
                    }
                }

                HorizontalDivider(color = DividerSubtle)

                // Sliders configurables de Ducking y Fade
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.VolumeDown,
                                contentDescription = null,
                                tint = TelemetryCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Volumen durante la intervención",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                        }
                        Text(
                            text = "${(engineState.duckingTargetVolume * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = TelemetryCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Slider(
                        value = engineState.duckingTargetVolume,
                        onValueChange = onDuckingVolumeChange,
                        valueRange = 0.05f..0.60f,
                        colors = SliderDefaults.colors(
                            thumbColor = TelemetryCyan,
                            activeTrackColor = TelemetryCyan,
                            inactiveTrackColor = DividerSubtle
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("slider_ducking_volume")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = null,
                                tint = TelemetryCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Duración del fade gradual",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                        }
                        Text(
                            text = String.format(Locale.US, "%.1f seg", engineState.fadeDurationSeconds),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = TelemetryCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Slider(
                        value = engineState.fadeDurationSeconds,
                        onValueChange = onFadeDurationChange,
                        valueRange = 0.3f..3.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = TelemetryCyan,
                            activeTrackColor = TelemetryCyan,
                            inactiveTrackColor = DividerSubtle
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("slider_fade_duration")
                    )

                    Text(
                        text = "Tip: Si volvés a tocar \"Probar locutor\" durante la locución, se cancela la voz de inmediato y la música recupera su volumen suavemente.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
        }
    }
}
