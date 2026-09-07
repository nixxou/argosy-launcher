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
 * Two screens, both fed by the server's own eligibility (Mehdi, 2026-09-07: "l'éligibilité doit
 * coller avec celle de génération des romm_id"):
 *  1. the versions — the game's own file and its alternatives. A tap on a version served whole pins
 *     it; a tap on an ELIGIBLE one (an archive the extractor takes apart for this platform/emulator)
 *     opens screen 2 instead, because only its roms carry a rom_id;
 *  2. the roms inside that one archive. A tap pins the rom.
 * A game with a single version that is eligible opens straight on screen 2 (there is nothing to
 * choose on screen 1), and B then leaves the picker. Otherwise B goes back one screen at a time and
 * leaves from screen 1 — always without pinning anything.
 *
 * Drilling in and pinning go through RomMRepository's liteBox* calls, thin wrappers over
 * RommLiteBoxApi.cs — see that file's own header for why an official RomM server never reaches any
 * of this (the feature simply is not there for it).
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
                    topLevelRows = withLocalFlags(result.data.map { it.toRow() })
                    val lone = topLevelRows.singleOrNull()
                    if (lone != null && lone.isDrillable) {
                        // One version and it is an archive to pick from: screen 1 would be a single
                        // line the user has to tap for no reason — open its roms directly.
                        drillInto(lone, hasVersionListBehind = false)
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false, rows = topLevelRows,
                                focusIndex = topLevelRows.indexOfFirst { r -> r.isCurrent }.coerceAtLeast(0),
                                drilledIntoLabel = null, archiveFileName = null, hasVersionListBehind = false
                            )
                        }
                    }
                }
                is RomMResult.Error -> {
                    Logger.warn(TAG, "listVersions failed: ${result.message}")
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
            }
        }
    }

    /** Marks the rows whose rom_id this device already holds a downloaded file for — the local row
     * for that id, when it exists and has a file. Rows without a rom_id yet (never pinned by anyone)
     * cannot be local: nothing was ever served under them. */
    private suspend fun withLocalFlags(rows: List<VersionPickerRow>): List<VersionPickerRow> = rows.map { row ->
        val id = row.romId ?: return@map row
        val local = gameDao.getByRommId(id)?.localPath != null
        if (local) row.copy(isLocal = true) else row
    }

    private fun moveFocus(delta: Int): Boolean {
        val state = _uiState.value
        if (state.rows.isEmpty()) return false
        val next = state.focusIndex + delta
        if (next < 0 || next >= state.rows.size) return false
        _uiState.update { it.copy(focusIndex = next) }
        return true
    }

    private fun drillInto(row: VersionPickerRow, hasVersionListBehind: Boolean = true) {
        if (_uiState.value.isApplying) return
        // Checked BEFORE the spinner goes up: bailing out after it would leave it up for good.
        val id = rommId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isApplying = true) }
            when (val result = romMRepository.liteBoxListRomsInVersion(id, row.appId)) {
                is RomMResult.Success -> {
                    val roms = withLocalFlags(result.data.roms.map { entry -> entry.toRow(row.appId) })
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isApplying = false,
                            drilledIntoLabel = result.data.versionLabel.ifBlank { row.label },
                            archiveFileName = result.data.archiveFileName.takeIf { name -> name.isNotBlank() },
                            hasVersionListBehind = hasVersionListBehind,
                            rows = roms,
                            // Land on what this device is served today, when it is in there.
                            focusIndex = roms.indexOfFirst { r -> r.isCurrent }.coerceAtLeast(0)
                        )
                    }
                }
                is RomMResult.Error -> {
                    Logger.warn(TAG, "listRomsInVersion failed: ${result.message}")
                    _uiState.update { it.copy(isLoading = false, isApplying = false, error = result.message) }
                }
            }
        }
    }

    /** One screen back. False when there is no screen behind this one — the caller then leaves. */
    private fun backOutOfDrillDown(): Boolean {
        val state = _uiState.value
        if (state.drilledIntoLabel == null || !state.hasVersionListBehind) return false
        _uiState.update {
            it.copy(
                rows = topLevelRows, drilledIntoLabel = null, archiveFileName = null, hasVersionListBehind = false,
                focusIndex = topLevelRows.indexOfFirst { r -> r.isCurrent }.coerceAtLeast(0)
            )
        }
        return true
    }

    /** A tap on a row: move the highlight there first (so the screen shows what is being acted on),
     * then do exactly what A does on the focused row — drill in, or pin. */
    fun selectRow(index: Int, onSwitched: (Long) -> Unit) {
        if (index !in _uiState.value.rows.indices) return
        _uiState.update { it.copy(focusIndex = index) }
        confirmFocused(onSwitched)
    }

    /**
     * Pins the focused row, re-syncs the game's platform, then hands the caller the game to show
     * next (Mehdi: "redirige automatiquement sur la nouvelle page du jeu post synchronisation
     * plateforme"). That is usually a DIFFERENT local game: the pin changes the rom_id this client
     * is served for the game (LiteBoxPinResponse.romId), so the platform sync retires the old row
     * and creates one for the new id — going back to the old Game Detail would show a game that no
     * longer exists, and its ViewModel would not reload even when the id happened to survive.
     * Falls back to the current game when the new rom is not found locally (sync failed, offline).
     * [onSwitched] fires only on success: staying on the picker after a failure is what lets the
     * user retry or pick something else.
     */
    private fun confirmFocused(onSwitched: (Long) -> Unit) {
        val state = _uiState.value
        if (state.isApplying) return
        val row = state.rows.getOrNull(state.focusIndex) ?: return
        if (row.isDrillable) { drillInto(row); return }

        val id = rommId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isApplying = true) }
            when (val result = romMRepository.liteBoxPinVersion(id, row.appId, row.path)) {
                is RomMResult.Success -> {
                    // Best-effort: the pin already holds server-side even if the re-sync that
                    // follows fails or the connection drops mid-way.
                    if (platformId >= 0) {
                        runCatching { romMRepository.syncPlatform(platformId) }
                            .onFailure { Logger.warn(TAG, "post-pin platform sync failed: ${it.message}") }
                    }
                    val newRomId = result.data.romId
                    val target = if (newRomId > 0) gameDao.getByRommId(newRomId)?.id else null
                    _uiState.update { it.copy(isApplying = false) }
                    onSwitched(target ?: gameId)
                }
                is RomMResult.Error -> {
                    Logger.warn(TAG, "pin failed: ${result.message}")
                    _uiState.update { it.copy(isApplying = false, error = result.message) }
                }
            }
        }
    }

    /** B goes back one screen when there is one behind; otherwise it leaves through [onBack]
     * explicitly — never relying on an UNHANDLED result reaching some other handler. */
    fun createInputHandler(onBack: () -> Unit, onSwitched: (Long) -> Unit): InputHandler = object : InputHandler {
        override fun onUp(): InputResult =
            if (moveFocus(-1)) InputResult.HANDLED else InputResult.handled(SoundType.BOUNDARY)

        override fun onDown(): InputResult =
            if (moveFocus(1)) InputResult.HANDLED else InputResult.handled(SoundType.BOUNDARY)

        override fun onConfirm(): InputResult {
            confirmFocused(onSwitched)
            return InputResult.HANDLED
        }

        override fun onBack(): InputResult {
            if (!backOutOfDrillDown()) onBack()
            return InputResult.HANDLED
        }
    }
}
