package com.example.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.MainActivity
import com.example.R
import com.example.ui.components.CollapsibleDebugPanel
import com.example.ui.components.NowPlayingSection
import com.example.ui.components.SongLibrarySection
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.AmberPrimaryContainer
import com.example.ui.theme.OnAirCoral
import com.example.ui.theme.OnAirCoralContainer
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VuGreen

private fun requestPinAppToHomeScreen(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported) {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pinShortcutInfo = ShortcutInfo.Builder(context, "radio_mia_main_launcher")
                .setShortLabel("Radio Mía")
                .setLongLabel("Abrir Radio Mía")
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(launchIntent)
                .build()
            shortcutManager.requestPinShortcut(pinShortcutInfo, null)
            return
        }
    }
    Toast.makeText(
        context,
        "Buscá 'Radio Mía' deslizando hacia arriba en tu cajón de aplicaciones.",
        Toast.LENGTH_LONG
    ).show()
}

@Composable
fun RadioMiaApp(
    radioViewModel: RadioViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by radioViewModel.uiState.collectAsStateWithLifecycle()
    val engineState = uiState.engineState

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        radioViewModel.onPermissionResult(granted)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Permiso opcional para la notificación de MediaSession en Android 13+ */ }

    LaunchedEffect(Unit) {
        if (!radioViewModel.checkAudioPermissionGranted()) {
            audioPermissionLauncher.launch(radioViewModel.requiredAudioPermission())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = uiState.activeSection == RadioSection.LIBRARY) {
        radioViewModel.selectSection(RadioSection.PLAYER)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = StudioBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            RadioStudioHeader(
                isInterventionActive = engineState.isInterventionActive,
                isPlaying = engineState.isPlaying,
                visibleUserStatus = engineState.visibleUserStatus,
                localSongsCount = uiState.localSongsCount,
                demoSongsCount = uiState.demoSongsCount,
                onQuickTestAnnouncer = { radioViewModel.triggerDjIntervention() },
                onPinToHomeScreen = { requestPinAppToHomeScreen(context) }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = StudioSurface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = uiState.activeSection == RadioSection.PLAYER,
                    onClick = { radioViewModel.selectSection(RadioSection.PLAYER) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Radio,
                            contentDescription = "Pantalla de reproducción"
                        )
                    },
                    label = { Text("Reproductor Al Aire") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                        selectedTextColor = AmberPrimary,
                        indicatorColor = AmberPrimary,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    ),
                    modifier = Modifier.testTag("nav_tab_player")
                )

                NavigationBarItem(
                    selected = uiState.activeSection == RadioSection.LIBRARY,
                    onClick = { radioViewModel.selectSection(RadioSection.LIBRARY) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.LibraryMusic,
                            contentDescription = "Lista de canciones"
                        )
                    },
                    label = { Text("Canciones (${uiState.filteredSongs.size})") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                        selectedTextColor = AmberPrimary,
                        indicatorColor = AmberPrimary,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    ),
                    modifier = Modifier.testTag("nav_tab_library")
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            CollapsibleDebugPanel(
                engineState = engineState,
                isExpanded = uiState.isDebugPanelExpanded,
                onToggleExpand = { radioViewModel.toggleDebugPanelExpanded() },
                onUpdateBackendUrl = { radioViewModel.updateBackendUrl(it) },
                onRefreshBackendStatus = { radioViewModel.checkBackendConnection() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val isWideScreen = maxWidth >= 720.dp

                if (isWideScreen) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NowPlayingSection(
                            engineState = engineState,
                            onTogglePlayPause = { radioViewModel.togglePlayPause() },
                            onPrevious = { radioViewModel.previousSong() },
                            onNext = { radioViewModel.nextSong() },
                            onSeekTo = { radioViewModel.seekTo(it) },
                            onMasterVolumeChange = { radioViewModel.updateMasterVolume(it) },
                            onDuckingVolumeChange = { radioViewModel.updateDuckingVolume(it) },
                            onAnnouncerVoiceVolumeChange = { radioViewModel.updateAnnouncerVoiceVolume(it) },
                            onFadeDurationChange = { radioViewModel.updateFadeDuration(it) },
                            onTriggerAnnouncer = { radioViewModel.triggerDjIntervention() },
                            onTestAnnouncer = { radioViewModel.triggerFallbackTestAnnouncer() },
                            onDjEnabledChange = { radioViewModel.setDjEnabled(it) },
                            onSelectRadioStyle = { radioViewModel.selectRadioStyle(it) },
                            onSelectInterventionType = { radioViewModel.selectInterventionType(it) },
                            onChangeAutoFrequency = { radioViewModel.setAutoInterventionFrequency(it) },
                            modifier = Modifier
                                .weight(0.55f)
                                .fillMaxHeight()
                        )

                        SongLibrarySection(
                            songs = uiState.filteredSongs,
                            currentSong = engineState.currentSong,
                            isPlaying = engineState.isPlaying,
                            hasAudioPermission = uiState.hasAudioPermission,
                            localSongsCount = uiState.localSongsCount,
                            demoSongsCount = uiState.demoSongsCount,
                            searchQuery = uiState.searchQuery,
                            onSearchQueryChange = { radioViewModel.setSearchQuery(it) },
                            onRequestPermission = {
                                audioPermissionLauncher.launch(radioViewModel.requiredAudioPermission())
                            },
                            onRefreshLibrary = { radioViewModel.refreshMusicLibrary() },
                            onSelectSong = { radioViewModel.selectAndPlaySong(it) },
                            modifier = Modifier
                                .weight(0.45f)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    when (uiState.activeSection) {
                        RadioSection.PLAYER -> {
                            NowPlayingSection(
                                engineState = engineState,
                                onTogglePlayPause = { radioViewModel.togglePlayPause() },
                                onPrevious = { radioViewModel.previousSong() },
                                onNext = { radioViewModel.nextSong() },
                                onSeekTo = { radioViewModel.seekTo(it) },
                                onMasterVolumeChange = { radioViewModel.updateMasterVolume(it) },
                                onDuckingVolumeChange = { radioViewModel.updateDuckingVolume(it) },
                                onAnnouncerVoiceVolumeChange = { radioViewModel.updateAnnouncerVoiceVolume(it) },
                                onFadeDurationChange = { radioViewModel.updateFadeDuration(it) },
                                onTriggerAnnouncer = { radioViewModel.triggerDjIntervention() },
                                onTestAnnouncer = { radioViewModel.triggerFallbackTestAnnouncer() },
                                onDjEnabledChange = { radioViewModel.setDjEnabled(it) },
                                onSelectRadioStyle = { radioViewModel.selectRadioStyle(it) },
                                onSelectInterventionType = { radioViewModel.selectInterventionType(it) },
                                onChangeAutoFrequency = { radioViewModel.setAutoInterventionFrequency(it) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        RadioSection.LIBRARY -> {
                            SongLibrarySection(
                                songs = uiState.filteredSongs,
                                currentSong = engineState.currentSong,
                                isPlaying = engineState.isPlaying,
                                hasAudioPermission = uiState.hasAudioPermission,
                                localSongsCount = uiState.localSongsCount,
                                demoSongsCount = uiState.demoSongsCount,
                                searchQuery = uiState.searchQuery,
                                onSearchQueryChange = { radioViewModel.setSearchQuery(it) },
                                onRequestPermission = {
                                    audioPermissionLauncher.launch(radioViewModel.requiredAudioPermission())
                                },
                                onRefreshLibrary = { radioViewModel.refreshMusicLibrary() },
                                onSelectSong = { radioViewModel.selectAndPlaySong(it) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioStudioHeader(
    isInterventionActive: Boolean,
    isPlaying: Boolean,
    visibleUserStatus: String,
    localSongsCount: Int,
    demoSongsCount: Int,
    onQuickTestAnnouncer: () -> Unit,
    onPinToHomeScreen: () -> Unit = {}
) {
    val badgeBgColor by animateColorAsState(
        targetValue = if (isInterventionActive) OnAirCoral else StudioSurfaceElevated,
        label = "header_badge_bg"
    )
    val badgeBorderColor by animateColorAsState(
        targetValue = if (isInterventionActive) OnAirCoral else AmberPrimary.copy(alpha = 0.6f),
        label = "header_badge_border"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = StudioSurface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cartel Luminoso "RADIO MÍA — AL AIRE" (sin frecuencias reales ni inventadas)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = badgeBgColor,
                    modifier = Modifier
                        .border(1.5.dp, badgeBorderColor, RoundedCornerShape(12.dp))
                        .testTag("header_on_air_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isInterventionActive -> TextPrimary
                                        isPlaying -> VuGreen
                                        else -> AmberPrimary
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "RADIO MÍA — AL AIRE",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        onClick = onPinToHomeScreen,
                        shape = RoundedCornerShape(10.dp),
                        color = StudioSurfaceElevated,
                        modifier = Modifier
                            .border(1.dp, AmberPrimary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .testTag("header_pin_home_shortcut")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AddToHomeScreen,
                                contentDescription = "Fijar ícono en Pantalla de Inicio",
                                tint = AmberPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = onQuickTestAnnouncer,
                        shape = RoundedCornerShape(10.dp),
                        color = if (isInterventionActive) OnAirCoralContainer else AmberPrimaryContainer,
                        modifier = Modifier
                            .border(
                                1.dp,
                                if (isInterventionActive) OnAirCoral else AmberPrimary,
                                RoundedCornerShape(10.dp)
                            )
                            .testTag("header_quick_test_announcer")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.RecordVoiceOver,
                                contentDescription = "Probar locutor",
                                tint = if (isInterventionActive) OnAirCoral else AmberPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isInterventionActive) "Cancelar" else "Probar locutor",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isInterventionActive) OnAirCoral else AmberPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Subtítulo compacto en una sola línea (evita que el texto se apile verticalmente en pantallas angostas)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Estado: $visibleUserStatus",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isInterventionActive) OnAirCoral else AmberPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = "${localSongsCount + demoSongsCount} temas disponibles",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1
                )
            }
        }
    }
}
