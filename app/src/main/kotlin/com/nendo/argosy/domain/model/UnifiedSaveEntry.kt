package com.nendo.argosy.domain.model

import com.nendo.argosy.util.formatAbsoluteTimestamp
import java.time.Instant

data class UnifiedSaveEntry(
    val localCacheId: Long? = null,
    val serverSaveId: Long? = null,
    val timestamp: Instant,
    val size: Long,
    val channelName: String? = null,
    val source: Source,
    val serverFileName: String? = null,
    val isLatest: Boolean = false,
    val isActive: Boolean = false,
    val isLocked: Boolean = false,
    val isHardcore: Boolean = false,
    val cheatsUsed: Boolean = false,
    val isRollback: Boolean = false,
    val isUserCreatedSlot: Boolean = false,
    val isCurrent: Boolean = false,
    val isArchival: Boolean = false,
    // The content's md5 (local cache first, else the server's content_hash); shown under the date on a LiteBox server.
    val contentHash: String? = null,
    // LiteBox only: the desktop's own description of this save (group, chip, copy title, live/copy).
    val liteboxLabel: String? = null
) {
    enum class Source { LOCAL, SERVER, BOTH }

    val isChannel: Boolean get() = isLocked && channelName != null
    val canBecomeChannel: Boolean get() = channelName == null
    val isServerOnly: Boolean get() = localCacheId == null && serverSaveId != null
    val canDeleteFromServer: Boolean get() = serverSaveId != null

    val displayName: String
        get() = when {
            isRollback -> "Rollback"
            channelName != null && isLatest -> "$channelName [Latest]"
            channelName != null -> channelName
            isLatest -> "Latest"
            else -> formatAbsoluteTimestamp(timestamp)
        }
}
