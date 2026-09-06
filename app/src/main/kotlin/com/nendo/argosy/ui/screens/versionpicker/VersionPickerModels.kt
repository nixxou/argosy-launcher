package com.nendo.argosy.ui.screens.versionpicker

import com.nendo.argosy.data.remote.romm.LiteBoxRomEntry
import com.nendo.argosy.data.remote.romm.LiteBoxVersion
import com.nendo.argosy.util.formatBytes

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
    subtitle = detailLine(label, fileName, size),
    isPinned = isPinned,
    isDrillable = mayHaveRoms,
    appId = appId,
    path = ""
)

internal fun LiteBoxRomEntry.toRow(appId: String) = VersionPickerRow(
    label = label,
    subtitle = detailLine(label, fileName, size),
    isPinned = isPinned,
    isDrillable = false,
    appId = appId,
    path = path
)

/** What tells two near-identical labels apart in a list of 92: the real file name (only when it
 * is not just the label again) and its size. Null when neither adds anything. */
private fun detailLine(label: String, fileName: String, size: Long): String? {
    val parts = buildList {
        if (fileName.isNotBlank() && !fileName.equals(label, ignoreCase = true)) add(fileName)
        if (size > 0) add(formatBytes(size))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
