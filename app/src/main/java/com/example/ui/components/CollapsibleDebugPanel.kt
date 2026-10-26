package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.DjInterventionPhase
import com.example.model.RadioEngineState
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.DividerSubtle
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.OnAirCoral
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VuGreen
import java.util.Locale

@Composable
fun CollapsibleDebugPanel(
    engineState: RadioEngineState,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onUpdateBackendUrl: (String) -> Unit = {},
    onRefreshBackendStatus: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var backendUrlDraft by remember(engineState.backendUrl) { mutableStateOf(engineState.backendUrl) }

    val shape = RoundedCornerShape(16.dp)
    val borderColor = if (engineState.isInterventionActive) {
        OnAirCoral.copy(alpha = 0.7f)
    } else {
        TelemetryCyan.copy(alpha = 0.35f)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, shape)
            .clip(shape)
            .testTag("debug_panel_container"),
        color = StudioSurfaceElevated.copy(alpha = 0.96f),
        tonalElevation = 6.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Cabecera siempre visible y plegable
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("debug_panel_toggle"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when (engineState.interventionPhase) {
                                    DjInterventionPhase.IDLE -> VuGreen
                                    DjInterventionPhase.SPEAKING -> OnAirCoral
                                    else -> AmberPrimary
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Filled.BugReport,
                        contentDescription = "Panel de depuración",
                        tint = TelemetryCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "PANEL DE DEPURACIÓN",
                            style = MaterialTheme.typography.labelLarge,
                            color = TelemetryCyan
                        )
                        Text(
                            text = "Estado: ${engineState.visibleUserStatus} • Vol: ${(engineState.currentRealtimeMusicVolume * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isExpanded) "Ocultar" else "Ver datos",
                        style = MaterialTheme.typography.labelSmall,
                        color = TelemetryCyan
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (isExpanded) "Contraer panel de depuración" else "Expandir panel de depuración",
                        tint = TelemetryCyan
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = DividerSubtle)

                    val songLabel = engineState.currentSong?.let { song ->
                        val origin = if (song.isBuiltInDemo) "Respaldo libre de derechos" else "MediaStore local"
                        "${song.title} ($origin)"
                    } ?: "Ninguna seleccionada"

                    DebugMetricRow(
                        label = "Canción actual:",
                        value = songLabel,
                        testTag = "debug_current_song"
                    )

                    DebugMetricRow(
                        label = "Volumen original:",
                        value = "${(engineState.originalVolumeBeforeIntervention * 100).toInt()}% (Maestro actual: ${(engineState.userMasterVolume * 100).toInt()}% | Instantáneo: ${(engineState.currentRealtimeMusicVolume * 100).toInt()}%)",
                        testTag = "debug_original_volume"
                    )

                    DebugMetricRow(
                        label = "Volumen durante la intervención:",
                        value = "Música: ${(engineState.duckingTargetVolume * 100).toInt()}% • Voz locutor: ${(engineState.announcerVoiceVolume * 100).toInt()}% (Fade: ${String.format(Locale.US, "%.1f", engineState.fadeDurationSeconds)} s)",
                        testTag = "debug_intervention_volume"
                    )

                    DebugMetricRow(
                        label = "Estado de reproducción:",
                        value = engineState.combinedPlaybackStatus,
                        valueColor = if (engineState.isInterventionActive) OnAirCoral else VuGreen,
                        testTag = "debug_playback_state"
                    )

                    val announcerDurSec = engineState.announcerDurationMs / 1000.0
                    val announcerPosSec = engineState.announcerPositionMs / 1000.0
                    DebugMetricRow(
                        label = "Duración del audio del locutor:",
                        value = String.format(
                            Locale.US,
                            "%.1f s / %.1f s (%d ms) • %s",
                            announcerPosSec,
                            announcerDurSec,
                            engineState.announcerDurationMs,
                            engineState.announcerAudioLabel
                        ),
                        testTag = "debug_announcer_duration"
                    )

                    DebugMetricRow(
                        label = "Groq / TTS / Caché:",
                        value = "Modelo: ${engineState.lastTextModelUsed} • Caché: ${if (engineState.lastCacheHit) "HIT" else "MISS"} • ${engineState.lastTtsStatus}",
                        testTag = "debug_groq_status"
                    )

                    DebugMetricRow(
                        label = "Detalle técnico backend:",
                        value = engineState.lastTechnicalDetail,
                        testTag = "debug_backend_detail"
                    )

                    // Configuración de URL del backend para emulador (10.0.2.2) o dispositivo físico en LAN
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = backendUrlDraft,
                            onValueChange = {
                                backendUrlDraft = it
                                onUpdateBackendUrl(it)
                            },
                            label = { Text("URL Backend Radio Mía", style = MaterialTheme.typography.labelSmall) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = TelemetryCyan,
                                unfocusedBorderColor = DividerSubtle,
                                focusedContainerColor = StudioSurface,
                                unfocusedContainerColor = StudioSurface
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("debug_backend_url_input")
                        )
                        IconButton(
                            onClick = onRefreshBackendStatus,
                            modifier = Modifier.testTag("debug_refresh_backend_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Sync,
                                contentDescription = "Verificar conexión con el backend",
                                tint = TelemetryCyan
                            )
                        }
                    }

                    // Barra visual del volumen en vivo para verificar el ducking y fade
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Nivel instantáneo de música (ExoPlayer 1)",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                            Text(
                                text = "${(engineState.currentRealtimeMusicVolume * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { engineState.currentRealtimeMusicVolume.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (engineState.isInterventionActive) OnAirCoral else AmberPrimary,
                            trackColor = DividerSubtle
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugMetricRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.weight(0.42f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(0.58f)
        )
    }
}

fun formatDurationMmSs(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
