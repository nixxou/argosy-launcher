package com.nendo.argosy.ui.common.savechannel

enum class SaveFocusColumn { SLOTS, HISTORY }

data class SaveSlotItem(
    val channelName: String?,
    val displayName: String,
    val isActive: Boolean,
    val saveCount: Int,
    val latestTimestamp: Long?,
    val isCreateAction: Boolean = false,
    val isMigrationCandidate: Boolean = false,
    val isArchivedBucket: Boolean = false
) {
    val slotKey: String get() = channelName ?: when {
        isCreateAction -> "__new_slot__"
        isArchivedBucket -> "__archived__"
        else -> "__none__"
    }
}

data class SaveHistoryItem(
    val cacheId: Long,
    val timestamp: Long,
    val size: Long,
    val channelName: String?,
    val isLocal: Boolean,
    val isSynced: Boolean,
    val isActiveRestorePoint: Boolean,
    val isLatest: Boolean,
    val isHardcore: Boolean,
    val isRollback: Boolean,
    val isArchival: Boolean = false,
    val serverSaveId: Long? = null,
    // LiteBox server only (Mehdi, 2026-09-08): the desktop's label for this save and the content md5,
    // shown under the date. liteBoxDetails is true for every row once the server proved to be LiteBox,
    // so local-only rows show their hash too.
    val liteboxLabel: String? = null,
    val contentHash: String? = null,
    val liteBoxDetails: Boolean = false
) {
    val historyKey: String get() =
        if (cacheId >= 0) "c$cacheId" else "s${serverSaveId ?: timestamp}"
}
