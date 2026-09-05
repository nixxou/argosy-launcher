package com.nendo.argosy.ui.screens.versionpicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nendo.argosy.core.input.SoundType
import com.nendo.argosy.data.local.dao.GameDao
import com.nendo.argosy.data.remote.romm.RomMRepository
import com.nendo.argosy.data.remote.romm.RomMResult
import com.nendo.argosy.ui.input.InputHandler
import com.nendo.argosy.ui.input.InputResult
import com.nendo.argosy.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "VersionPickerViewModel"

/**
 * The full-page version/rom picker Mehdi asked for in place of a small modal — a game can offer
 * thousands of choices here, which is exactly why this is a real screen (its own LazyColumn, its
 * own InputHandler subscription) rather than one more of Game Detail's small in-place pickers.
 *
 * Drilling into a version and pinning both go through RomMRepository's liteBox* calls, which are
 * themselves thin wrappers over RommLiteBoxApi.cs — see that file's own header for why an official
 * RomM server never reaches any of this (the feature simply is not there for it).
 */
@HiltViewModel
class VersionPickerViewModel @Inject constructor(
    private val gameDao: GameDao,
    private val romMRepository: RomMRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(VersionPickerUiState())
    val uiState: StateFlow<VersionPickerUiState> = _uiState.asStateFlow()

    private var gameId: Long = -1L
    private var rommId: Long? = null
    private var platformId: Long = -1L

    // The top-level list, kept aside so "back" out of a drilled-in version can restore it without a
    // second network round trip for something we already have.
    private var topLevelRows: List<VersionPickerRow> = emptyList()

    fun load(gameId: Long) {
        this.gameId = gameId
        viewModelScope.launch {
            val game = gameDao.getById(gameId)
            if (game == null || game.rommId == null) {
                _uiState.update { it.copy(isLoading = false, error = "Not a RomM-tracked game") }
                return@launch
            }
            rommId = game.rommId
            platformId = game.platformId
            _uiState.update { it.copy(isLoading = true, gameTitle = game.title, error = null) }

            when (val result = romMRepository.liteBoxListVersions(game.rommId)) {
                is RomMResult.Success -> {
                    topLevelRows = result.data.map { it.toRow() }
                    _uiState.update {
                        it.copy(isLoading = false, rows = topLevelRows, focusIndex = 0, drilledIntoLabel = null)
                    }
                }
                is RomMResult.Error -> {
                    Logger.warn(TAG, "listVersions failed: ${result.message}")
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
            }
        }
    }

    private fun moveFocus(delta: Int): Boolean {
        val state = _uiState.value
        if (state.rows.isEmpty()) return false
        val next = state.focusIndex + delta
        if (next < 0 || next >= state.rows.size) return false
        _uiState.update { it.copy(focusIndex = next) }
        return true
    }

    private fun drillInto(row: VersionPickerRow) {
        if (_uiState.value.isApplying) return
        viewModelScope.launch {
            _uiState.update { it.copy(isApplying = true) }
            val id = rommId ?: return@launch
            when (val result = romMRepository.liteBoxListRomsInVersion(id, row.appId)) {
                is RomMResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isApplying = false,
                            drilledIntoLabel = row.label,
                            rows = result.data.map { entry -> entry.toRow(row.appId) },
                            focusIndex = 0
                        )
                    }
                }
                is RomMResult.Error -> {
                    Logger.warn(TAG, "listRomsInVersion failed: ${result.message}")
                    _uiState.update { it.copy(isApplying = false, error = result.message) }
                }
            }
        }
    }

    private fun backOutOfDrillDown(): Boolean {
        if (_uiState.value.drilledIntoLabel == null) return false
        _uiState.update { it.copy(rows = topLevelRows, drilledIntoLabel = null, focusIndex = 0) }
        return true
    }

    /**
     * Pins the focused row, then re-syncs the game's platform (Mehdi: "redirige automatiquement sur
     * la nouvelle page du jeu post synchronisation plateforme") before handing control back to the
     * caller. [onDone] runs regardless of outcome — a failed pin still needs the screen to stop
     * showing a spinner — [onNavigateBack] only on success, since staying on the picker after a
     * failure is what lets the user retry or pick something else.
     */
    private fun confirmFocused(onNavigateBack: () -> Unit) {
        val state = _uiState.value
        if (state.isApplying) return
        val row = state.rows.getOrNull(state.focusIndex) ?: return
        if (row.isDrillable) { drillInto(row); return }

        val id = rommId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isApplying = true) }
            val result = romMRepository.liteBoxPinVersion(id, row.appId, row.path)
            when (result) {
                is RomMResult.Success -> {
                    // Best-effort: a game still shows correctly off the pin alone even if the
                    // platform re-sync that follows fails or the connection drops mid-way.
                    if (platformId >= 0) {
                        runCatching { romMRepository.syncPlatform(platformId) }
                            .onFailure { Logger.warn(TAG, "post-pin platform sync failed: ${it.message}") }
                    }
                    _uiState.update { it.copy(isApplying = false) }
                    onNavigateBack()
                }
                is RomMResult.Error -> {
                    Logger.warn(TAG, "pin failed: ${result.message}")
                    _uiState.update { it.copy(isApplying = false, error = result.message) }
                }
            }
        }
    }

    fun createInputHandler(onBack: () -> Unit): InputHandler = object : InputHandler {
        override fun onUp(): InputResult =
            if (moveFocus(-1)) InputResult.HANDLED else InputResult.handled(SoundType.BOUNDARY)

        override fun onDown(): InputResult =
            if (moveFocus(1)) InputResult.HANDLED else InputResult.handled(SoundType.BOUNDARY)

        override fun onConfirm(): InputResult {
            confirmFocused(onNavigateBack = onBack)
            return InputResult.HANDLED
        }

        override fun onBack(): InputResult =
            if (backOutOfDrillDown()) InputResult.HANDLED else InputResult.UNHANDLED
    }
}
