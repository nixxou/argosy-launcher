package com.nendo.argosy.ui.screens.versionpicker

import com.nendo.argosy.data.remote.romm.LiteBoxRomEntry
import com.nendo.argosy.data.remote.romm.LiteBoxVersion

/**
 * One flattened row the picker renders — either a top-level version or, once drilled into one, a
 * rom inside it. A single shape keeps the LazyColumn and its focus index oblivious to which level
 * is showing, the same way GameDetail's own pickers stay index-based rather than widget-based.
 */
data class VersionPickerRow(
    val label: String,
    val subtitle: String?,
    val isPinned: Boolean,
    val isDrillable: Boolean,
    val appId: String,
    val path: String
)

data class VersionPickerUiState(
    val isLoading: Boolean = true,
    val gameTitle: String = "",
    // Empty while showing the top-level version list; the version's own label once drilled in.
    val drilledIntoLabel: String? = null,
    val rows: List<VersionPickerRow> = emptyList(),
    val focusIndex: Int = 0,
    val isApplying: Boolean = false,
    val error: String? = null
)

internal fun LiteBoxVersion.toRow() = VersionPickerRow(
    label = label,
    subtitle = null,
    isPinned = isPinned,
    isDrillable = mayHaveRoms,
    appId = appId,
    path = ""
)

internal fun LiteBoxRomEntry.toRow(appId: String) = VersionPickerRow(
    label = label,
    subtitle = null,
    isPinned = isPinned,
    isDrillable = false,
    appId = appId,
    path = path
)
