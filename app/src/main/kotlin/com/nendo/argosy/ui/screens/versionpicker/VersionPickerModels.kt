package com.nendo.argosy.ui.screens.versionpicker

import com.nendo.argosy.data.remote.romm.LiteBoxRomEntry
import com.nendo.argosy.data.remote.romm.LiteBoxVersion
import com.nendo.argosy.util.formatBytes

/**
 * One flattened row the picker renders — either a top-level version or, once drilled into one, a
 * rom inside it. A single shape keeps the LazyColumn and its focus index oblivious to which level
 * is showing, the same way GameDetail's own pickers stay index-based rather than widget-based.
 *
 * [isDrillable] is the server's eligibility verdict (RommFiles.CandidatesOf's IsExtract — the rule
 * the rom_id generation itself uses), never guessed here from an extension. [romId] is the id this
 * choice already has, when it has one; [isLocal] says the file for that id is already downloaded.
 */
data class VersionPickerRow(
    val label: String,
    val subtitle: String?,
    val isCurrent: Boolean,
    val isPinned: Boolean,
    val isDrillable: Boolean,
    val romCount: Int?,
    val appId: String,
    val path: String,
    val romId: Long?,
    val isLocal: Boolean = false,
    val isFavorite: Boolean = false,
    val isLastPlayed: Boolean = false,
    val score: Int = 0,
    val hasRa: Boolean = false
)

data class VersionPickerUiState(
    val isLoading: Boolean = true,
    val gameTitle: String = "",
    // Null while showing the top-level version list; the version's own label once drilled in.
    val drilledIntoLabel: String? = null,
    // The archive the second screen's roms come from — shown under the title so the user knows.
    val archiveFileName: String? = null,
    // False when the second screen is the ONLY screen (a lone eligible archive opened directly):
    // B then leaves the picker instead of going "back" to a one-line list nobody chose from.
    val hasVersionListBehind: Boolean = false,
    val rows: List<VersionPickerRow> = emptyList(),
    val focusIndex: Int = 0,
    val isApplying: Boolean = false,
    val error: String? = null
)

internal fun LiteBoxVersion.toRow() = VersionPickerRow(
    label = label,
    subtitle = detailLine(label, fileName, size),
    isCurrent = isCurrent,
    isPinned = isPinned,
    isDrillable = eligible,
    romCount = if (eligible) romCount else null,
    appId = appId,
    path = "",
    romId = romId
)

internal fun LiteBoxRomEntry.toRow(appId: String) = VersionPickerRow(
    label = label,
    subtitle = detailLine(label, fileName, size),
    isCurrent = isCurrent,
    isPinned = isPinned,
    isDrillable = false,
    romCount = null,
    appId = appId,
    path = path,
    romId = romId,
    isFavorite = isFavorite,
    isLastPlayed = isLastPlayed,
    score = score,
    hasRa = hasRa
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
