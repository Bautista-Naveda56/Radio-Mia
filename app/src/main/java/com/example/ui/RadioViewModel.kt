package com.example.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.LocalAudioRepository
import com.example.model.InterventionType
import com.example.model.RadioEngineState
import com.example.model.RadioStyle
import com.example.model.SongItem
import com.example.service.RadioAudioEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class RadioSection {
    PLAYER,
    LIBRARY
}

data class RadioScreenUiState(
    val engineState: RadioEngineState = RadioEngineState(),
    val hasAudioPermission: Boolean = false,
    val localSongsCount: Int = 0,
    val demoSongsCount: Int = 0,
    val searchQuery: String = "",
    val filteredSongs: List<SongItem> = emptyList(),
    val activeSection: RadioSection = RadioSection.PLAYER,
    val isDebugPanelExpanded: Boolean = false,
    val isLoadingLibrary: Boolean = false
)

class RadioViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val repository = LocalAudioRepository(appContext)
    private val audioEngine = RadioAudioEngine.getInstance(appContext)

    private val _hasAudioPermission = MutableStateFlow(checkAudioPermissionGranted())
    private val _searchQuery = MutableStateFlow("")
    private val _activeSection = MutableStateFlow(RadioSection.PLAYER)
    private val _isDebugPanelExpanded = MutableStateFlow(false)
    private val _isLoadingLibrary = MutableStateFlow(false)
    private val _localSongsCount = MutableStateFlow(0)
    private val _demoSongsCount = MutableStateFlow(0)

    val uiState: StateFlow<RadioScreenUiState> = combine(
        audioEngine.state,
        _hasAudioPermission,
        _searchQuery,
        _activeSection,
        _isDebugPanelExpanded,
        _isLoadingLibrary,
        _localSongsCount,
        _demoSongsCount
    ) { values ->
        val engine = values[0] as RadioEngineState
        val hasPerm = values[1] as Boolean
        val query = values[2] as String
        val section = values[3] as RadioSection
        val debugExpanded = values[4] as Boolean
        val loading = values[5] as Boolean
        val localCount = values[6] as Int
        val demoCount = values[7] as Int

        val filtered = if (query.isBlank()) {
            engine.playlist
        } else {
            val q = query.trim().lowercase()
            engine.playlist.filter {
                it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q)
            }
        }

        RadioScreenUiState(
            engineState = engine,
            hasAudioPermission = hasPerm,
            localSongsCount = localCount,
            demoSongsCount = demoCount,
            searchQuery = query,
            filteredSongs = filtered,
            activeSection = section,
            isDebugPanelExpanded = debugExpanded,
            isLoadingLibrary = loading
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RadioScreenUiState()
    )

    init {
        refreshMusicLibrary()
    }

    fun requiredAudioPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    fun checkAudioPermissionGranted(): Boolean {
        val perm = requiredAudioPermission()
        return ContextCompat.checkSelfPermission(appContext, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun onPermissionResult(granted: Boolean) {
        _hasAudioPermission.value = granted || checkAudioPermissionGranted()
        refreshMusicLibrary()
    }

    fun refreshMusicLibrary() {
        viewModelScope.launch {
            _isLoadingLibrary.value = true
            val hasPerm = checkAudioPermissionGranted()
            _hasAudioPermission.value = hasPerm

            val localSongs = if (hasPerm) {
                repository.queryLocalSongsFromMediaStore()
            } else {
                emptyList()
            }
            val demoSongs = repository.getBuiltInRoyaltyFreeSongs()

            _localSongsCount.value = localSongs.size
            _demoSongsCount.value = demoSongs.size

            val combined = localSongs + demoSongs
            audioEngine.setPlaylist(combined, autoSelectFirst = true)
            _isLoadingLibrary.value = false
        }
    }

    fun selectAndPlaySong(song: SongItem) {
        audioEngine.playSong(song)
        _activeSection.value = RadioSection.PLAYER
    }

    fun togglePlayPause() {
        audioEngine.togglePlayPause()
    }

    fun nextSong() {
        audioEngine.skipToNext()
    }

    fun previousSong() {
        audioEngine.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        audioEngine.seekTo(positionMs)
    }

    fun updateMasterVolume(volume: Float) {
        audioEngine.setMasterVolume(volume)
    }

    fun updateDuckingVolume(volume: Float) {
        audioEngine.setDuckingTargetVolume(volume)
    }

    fun updateAnnouncerVoiceVolume(volume: Float) {
        audioEngine.setAnnouncerVoiceVolume(volume)
    }

    fun updateFadeDuration(seconds: Float) {
        audioEngine.setFadeDurationSeconds(seconds)
    }

    fun triggerDjIntervention() {
        audioEngine.triggerOrToggleAnnouncer(useGroqBackend = true)
    }

    fun triggerFallbackTestAnnouncer() {
        audioEngine.triggerOrToggleAnnouncer(useGroqBackend = true)
    }

    fun setDjEnabled(enabled: Boolean) {
        audioEngine.setDjEnabled(enabled)
    }

    fun selectRadioStyle(style: RadioStyle) {
        audioEngine.setRadioStyle(style)
    }

    fun selectInterventionType(type: InterventionType) {
        audioEngine.setInterventionType(type)
    }

    fun setAutoInterventionFrequency(everySongs: Int) {
        audioEngine.setAutoInterventionFrequency(everySongs)
    }

    fun updateBackendUrl(url: String) {
        audioEngine.setBackendUrl(url)
    }

    fun checkBackendConnection() {
        audioEngine.refreshBackendStatus()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectSection(section: RadioSection) {
        _activeSection.value = section
    }

    fun toggleDebugPanelExpanded() {
        _isDebugPanelExpanded.value = !_isDebugPanelExpanded.value
    }
}
