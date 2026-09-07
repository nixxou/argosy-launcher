package com.nendo.argosy.util

import android.util.Log as AndroidLog
import com.nendo.argosy.util.SafeCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object SaveDebugLogger {
    private const val TAG = "SaveDebugLogger"

    private val scope = SafeCoroutineScope(Dispatchers.IO, "SaveDebugLogger")
    private val logChannel = Channel<SaveLogEntry>(Channel.BUFFERED)

    private var fileWriter: FileWriter? = null
    private var currentLogDate: LocalDate? = null
    private var logDirectory: String? = null
    private var versionName: String = "unknown"

    @Volatile
    private var enabled = false

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    val isEnabled: Boolean get() = enabled

    init {
        scope.launch {
            logChannel.receiveAsFlow().collect { entry ->
                writeToFile(entry)
            }
        }
    }

    fun configure(versionName: String, logDirectory: String?, enabled: Boolean) {
        this.versionName = versionName
        this.logDirectory = logDirectory
        this.enabled = enabled && logDirectory != null

        if (!this.enabled) {
            closeCurrentFile()
        }

        if (this.enabled) {
            AndroidLog.i(TAG, "Save debug logging enabled, output: $logDirectory/saves-*.log")
        }
    }

    fun logCacheCreated(
        gameId: Long,
        gameName: String?,
        channel: String?,
        sizeBytes: Long,
        contentHash: String,
        isHardcore: Boolean,
        needsRemoteSync: Boolean,
        emulatorId: String
    ) {
        log(
            event = "CACHE_CREATED",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = buildString {
                append("size=${formatBytesStable(sizeBytes)}")
                append(", hash=${contentHash.take(12)}...")
                append(", hardcore=$isHardcore")
                append(", needsSync=$needsRemoteSync")
                append(", emulator=$emulatorId")
            }
        )
    }

    fun logCacheDuplicate(
        gameId: Long,
        gameName: String?,
        channel: String?,
        contentHash: String
    ) {
        log(
            event = "CACHE_DUPLICATE",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = "hash=${contentHash.take(12)}... (skipped)"
        )
    }

    fun logCacheRestored(
        gameId: Long,
        gameName: String?,
        channel: String?,
        cacheId: Long,
        targetPath: String
    ) {
        log(
            event = "CACHE_RESTORED",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = "cacheId=$cacheId, target=${File(targetPath).name}"
        )
    }

    fun logCacheDeleted(
        gameId: Long,
        gameName: String?,
        channel: String?,
        cacheId: Long,
        reason: String
    ) {
        log(
            event = "CACHE_DELETED",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = "cacheId=$cacheId, reason=$reason"
        )
    }

    fun logCachePruned(
        gameId: Long,
        gameName: String?,
        prunedCount: Int,
        remainingCount: Int
    ) {
        log(
            event = "CACHE_PRUNED",
            gameId = gameId,
            gameName = gameName,
            channel = null,
            details = "deleted=$prunedCount, remaining=$remainingCount"
        )
    }

    fun logSyncUploadStarted(
        gameId: Long,
        gameName: String?,
        channel: String?,
        sizeBytes: Long,
        contentHash: String
    ) {
        log(
            event = "SYNC_UPLOAD_START",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = "size=${formatBytesStable(sizeBytes)}, hash=${contentHash.take(12)}..."
        )
    }

    fun logSyncUploadCompleted(
        gameId: Long,
        gameName: String?,
        channel: String?,
        serverId: Long?,
        durationMs: Long
    ) {
        log(
            event = "SYNC_UPLOAD_OK",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = "serverId=$serverId, duration=${durationMs}ms"
        )
    }

    fun logSyncUploadFailed(
        gameId: Long,
        gameName: String?,
        channel: String?,
        error: String
    ) {
        log(
            event = "SYNC_UPLOAD_FAIL",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = "error=$error"
        )
    }

    fun logSyncDownloadStarted(
        gameId: Long,
        gameName: String?,
        channel: String?,
        serverId: Long
    ) {
        log(
            event = "SYNC_DOWNLOAD_START",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = "serverId=$serverId"
        )
    }

    fun logSyncDownloadCompleted(
        gameId: Long,
        gameName: String?,
        channel: String?,
        sizeBytes: Long,
        durationMs: Long
    ) {
        log(
            event = "SYNC_DOWNLOAD_OK",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = "size=${formatBytesStable(sizeBytes)}, duration=${durationMs}ms"
        )
    }

    fun logSyncDownloadFailed(
        gameId: Long,
        gameName: String?,
        channel: String?,
        error: String
    ) {
        log(
            event = "SYNC_DOWNLOAD_FAIL",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = "error=$error"
        )
    }

    fun logSyncStatusChanged(
        gameId: Long,
        gameName: String?,
        channel: String?,
        oldStatus: String?,
        newStatus: String,
        reason: String? = null
    ) {
        log(
            event = "SYNC_STATUS",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = buildString {
                append("$oldStatus -> $newStatus")
                if (reason != null) append(" ($reason)")
            }
        )
    }

    fun logConflictDetected(
        gameId: Long,
        gameName: String?,
        channel: String?,
        localHash: String?,
        serverHash: String?,
        localTime: Instant?,
        serverTime: Instant?
    ) {
        log(
            event = "SYNC_CONFLICT",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = buildString {
                append("localHash=${localHash?.take(12) ?: "null"}...")
                append(", serverHash=${serverHash?.take(12) ?: "null"}...")
                append(", localTime=${localTime?.let { formatInstant(it) } ?: "null"}")
                append(", serverTime=${serverTime?.let { formatInstant(it) } ?: "null"}")
            }
        )
    }

    fun logConflictResolved(
        gameId: Long,
        gameName: String?,
        channel: String?,
        resolution: String
    ) {
        log(
            event = "SYNC_CONFLICT_RESOLVED",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = "resolution=$resolution"
        )
    }

    fun logSessionStart(
        gameId: Long,
        gameName: String?,
        channel: String?,
        isHardcore: Boolean,
        existingSaveHash: String?
    ) {
        log(
            event = "SESSION_START",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = buildString {
                append("hardcore=$isHardcore")
                append(", existingHash=${existingSaveHash?.take(12) ?: "none"}...")
            }
        )
    }

    fun logSessionEnd(
        gameId: Long,
        gameName: String?,
        channel: String?,
        durationSec: Long,
        saveChanged: Boolean,
        newHash: String?
    ) {
        log(
            event = "SESSION_END",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = buildString {
                append("duration=${durationSec}s")
                append(", saveChanged=$saveChanged")
                if (newHash != null) append(", newHash=${newHash.take(12)}...")
            }
        )
    }

    fun logChannelCreated(
        gameId: Long,
        gameName: String?,
        channel: String,
        fromCacheId: Long?
    ) {
        log(
            event = "CHANNEL_CREATED",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = if (fromCacheId != null) "fromCache=$fromCacheId" else "new"
        )
    }

    fun logChannelDeleted(
        gameId: Long,
        gameName: String?,
        channel: String
    ) {
        log(
            event = "CHANNEL_DELETED",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = null
        )
    }

    fun logChannelSwitched(
        gameId: Long,
        gameName: String?,
        fromChannel: String?,
        toChannel: String?
    ) {
        log(
            event = "CHANNEL_SWITCHED",
            gameId = gameId,
            gameName = gameName,
            channel = toChannel,
            details = "from=${fromChannel ?: "default"} to=${toChannel ?: "default"}"
        )
    }

    fun logError(
        operation: String,
        gameId: Long?,
        gameName: String?,
        channel: String?,
        error: Throwable
    ) {
        log(
            event = "ERROR",
            gameId = gameId,
            gameName = gameName,
            channel = channel,
            details = "$operation: ${error.message}"
        )
    }

    fun logCustom(
        event: String,
        gameId: Long?,
        gameName: String?,
        channel: String?,
        details: String?
    ) {
        log(event, gameId, gameName, channel, details)
    }

    fun logDiscoverPath(
        gameId: Long,
        emulatorId: String,
        emulatorPackage: String?,
        platformSlug: String,
        romPath: String?,
        cachedSaveId: String?,
        selectedMemcardPath: String?,
        savePathOverride: String?,
        resultPath: String?,
        decision: String
    ) {
        log(
            event = "DISCOVER_PATH",
            gameId = gameId,
            gameName = null,
            channel = null,
            details = buildString {
                append("emu=$emulatorId(pkg=$emulatorPackage), platform=$platformSlug")
                append(", rom=${romPath?.let { File(it).name } ?: "null"}")
                append(", saveId=$cachedSaveId")
                if (selectedMemcardPath != null) append(", selectedCard=${File(selectedMemcardPath).name}")
                if (savePathOverride != null) append(", override=${File(savePathOverride).name}")
                append(", decision=$decision")
                append(", result=${resultPath ?: "null"}")
            }
        )
    }

    fun logRestoreVerify(
        gameId: Long,
        cacheId: Long,
        targetPath: String,
        expectedHash: String?,
        actualHash: String?,
        match: Boolean
    ) {
        log(
            event = if (match) "RESTORE_VERIFY_OK" else "RESTORE_VERIFY_MISMATCH",
            gameId = gameId,
            gameName = null,
            channel = null,
            details = buildString {
                append("cacheId=$cacheId")
                append(", target=${File(targetPath).name}")
                append(", expected=${expectedHash?.take(12) ?: "null"}")
                append(", actual=${actualHash?.take(12) ?: "null"}")
            }
        )
    }

    fun logUploadHash(
        gameId: Long,
        channel: String?,
        sourcePath: String,
        diskHash: String?,
        expectedCacheId: Long?,
        expectedCacheHash: String?
    ) {
        val match = diskHash != null && expectedCacheHash != null && diskHash == expectedCacheHash
        log(
            event = if (expectedCacheId != null && !match) "UPLOAD_HASH_MISMATCH" else "UPLOAD_HASH",
            gameId = gameId,
            gameName = null,
            channel = channel,
            details = buildString {
                append("source=${File(sourcePath).name}")
                append(", diskHash=${diskHash?.take(12) ?: "null"}")
                append(", expectedCacheId=$expectedCacheId")
                append(", expectedHash=${expectedCacheHash?.take(12) ?: "null"}")
            }
        )
    }

    fun logLinkCache(
        gameId: Long,
        channel: String?,
        cacheId: Long?,
        rommSaveId: Long?,
        serverTimestamp: Instant?,
        method: String
    ) {
        log(
            event = "LINK_CACHE",
            gameId = gameId,
            gameName = null,
            channel = channel,
            details = buildString {
                append("cacheId=$cacheId, rommSaveId=$rommSaveId")
                append(", serverTime=${serverTimestamp?.let { formatInstant(it) } ?: "null"}")
                append(", via=$method")
            }
        )
    }

    fun logLiveCacheObserve(
        gameId: Long,
        eventType: Int,
        path: String?
    ) {
        log(
            event = "LIVE_OBSERVE",
            gameId = gameId,
            gameName = null,
            channel = null,
            details = "evt=$eventType, path=$path"
        )
    }

    fun logLiveCacheFire(gameId: Long, savePath: String?) {
        log(
            event = "LIVE_CACHE_FIRE",
            gameId = gameId,
            gameName = null,
            channel = null,
            details = "savePath=${savePath?.let { File(it).name } ?: "null"}"
        )
    }

    fun logSessionSyncSkip(gameId: Long, reason: String) {
        log(
            event = "SESSION_SYNC_SKIP",
            gameId = gameId,
            gameName = null,
            channel = null,
            details = reason
        )
    }

    fun logUnifiedBuilt(
        gameId: Long,
        totalLocal: Int,
        totalServer: Int,
        mergedBoth: Int,
        localOnly: Int,
        serverOnly: Int,
        unmatchedServerByClaimedSlot: Int,
        serverDeviceBreakdown: Map<String?, Int> = emptyMap()
    ) {
        log(
            event = "UNIFIED_BUILT",
            gameId = gameId,
            gameName = null,
            channel = null,
            details = buildString {
                append("local=$totalLocal, server=$totalServer")
                append(" -> both=$mergedBoth, localOnly=$localOnly, serverOnly=$serverOnly")
                if (unmatchedServerByClaimedSlot > 0) {
                    append(", droppedServer(slotClaimed)=$unmatchedServerByClaimedSlot")
                }
                if (serverDeviceBreakdown.isNotEmpty()) {
                    append(", deviceIds=[")
                    serverDeviceBreakdown.entries.joinToString(",") { (devId, count) ->
                        "${devId ?: "null"}:$count"
                    }.let(::append)
                    append("]")
                }
            }
        )
    }

    fun logEmulatorResolved(
        gameId: Long,
        emulatorId: String?,
        emulatorPackage: String?,
        source: String
    ) {
        log(
            event = "EMULATOR_RESOLVED",
            gameId = gameId,
            gameName = null,
            channel = null,
            details = "emu=$emulatorId, pkg=$emulatorPackage, source=$source"
        )
    }

    fun logChannelLatestPick(
        gameId: Long,
        channel: String?,
        pickedCacheId: Long?,
        candidateCount: Int,
        candidateIds: List<Long>
    ) {
        log(
            event = "CHANNEL_LATEST_PICK",
            gameId = gameId,
            gameName = null,
            channel = channel,
            details = buildString {
                append("picked=$pickedCacheId of $candidateCount")
                if (candidateIds.size > 1) append(", candidates=${candidateIds.take(5)}")
            }
        )
    }

    fun logRestoreEntryPicked(
        gameId: Long,
        channel: String?,
        localCacheId: Long?,
        serverSaveId: Long?,
        entryTimestamp: Instant?,
        source: String,
        isLatest: Boolean
    ) {
        log(
            event = "RESTORE_ENTRY_PICKED",
            gameId = gameId,
            gameName = null,
            channel = channel,
            details = buildString {
                append("source=$source")
                append(", cacheId=$localCacheId, rommSaveId=$serverSaveId")
                append(", entryTime=${entryTimestamp?.let { formatInstant(it) } ?: "null"}")
                append(", isLatest=$isLatest")
            }
        )
    }

    // ── Restore paths (Mehdi, 2026-09-08): every file a save/state restore reads or writes, with its
    // full path and hash. Unlike the sync events above these keep the FULL path on purpose: the
    // question they answer is "where did it land, under what name, with what content". Cost is paid
    // only while the option is on - callers guard hashing with isEnabled.

    /** MD5 of a file, or a size-only summary for a folder; null when unreadable. Only while enabled. */
    fun describeFile(path: String?): String {
        if (path == null) return "null"
        val f = File(path)
        if (!f.exists()) return "$path (missing)"
        if (f.isDirectory) {
            val files = f.walkTopDown().filter { it.isFile }.toList()
            return "$path (dir, ${files.size} files, ${formatBytesStable(files.sumOf { it.length() })})"
        }
        val hash = if (enabled) fileHash(f) else null
        return "$path (${formatBytesStable(f.length())}, md5=${hash ?: "?"}, mtime=${formatMillis(f.lastModified())})"
    }

    fun fileHash(file: File): String? = try {
        val md = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                md.update(buffer, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }

    fun bytesHash(data: ByteArray): String =
        java.security.MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

    private fun formatMillis(ms: Long): String =
        if (ms <= 0) "-" else DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

    /** A save restore (manual, from the save-management screen) starts: what was picked and where it lands. */
    fun logRestoreSaveBegin(gameId: Long, channel: String?, source: String, cacheId: Long?, serverSaveId: Long?,
                            emulatorId: String, targetPath: String, previousHash: String?) {
        log("RESTORE_SAVE_BEGIN", gameId, null, channel,
            "source=$source, cacheId=$cacheId, rommSaveId=$serverSaveId, emulator=$emulatorId" +
            ", target=${describeFile(targetPath)}, previousHash=${previousHash ?: "null"}")
    }

    fun logRestoreSaveDone(gameId: Long, channel: String?, targetPath: String, restoredHash: String?,
                           previousHash: String?, activated: String) {
        val changed = if (previousHash == null || restoredHash == null) "unknown" else (previousHash != restoredHash).toString()
        log("RESTORE_SAVE_DONE", gameId, null, channel,
            "target=${describeFile(targetPath)}, restoredHash=${restoredHash ?: "null"}, changed=$changed, active=$activated")
    }

    fun logRestoreSaveFailed(gameId: Long, channel: String?, reason: String) {
        log("RESTORE_SAVE_FAILED", gameId, null, channel, "reason=$reason")
    }

    /** The stale-resume guard's decision: did a content change wipe the built-in core's auto/resume states? */
    fun logResumeStateGuard(gameId: Long?, emulatorId: String, trigger: String, previousHash: String?,
                            newHash: String?, decision: String) {
        log("RESUME_STATE_GUARD", gameId, null, null,
            "trigger=$trigger, emulator=$emulatorId, previous=${previousHash?.take(12) ?: "null"}" +
            ", new=${newHash?.take(12) ?: "null"}, decision=$decision")
    }

    fun logLiveStateDeleted(gameId: Long?, path: String, sizeBytes: Long) {
        log("LIVE_STATE_DELETED", gameId, null, null, "file=$path (${formatBytesStable(sizeBytes)})")
    }

    fun logLiveStateSweep(gameId: Long?, emulatorId: String, dirs: List<String>, names: List<String>, deleted: Int) {
        log("LIVE_STATE_SWEEP", gameId, null, null,
            "emulator=$emulatorId, deleted=$deleted, names=$names, dirs=$dirs")
    }

    /** A cached save state is about to be written back to where the emulator reads it. */
    fun logStateRestoreBegin(gameId: Long, cacheId: Long, slot: Int, channel: String?, emulatorId: String,
                             cachedCore: String?, cachedVersion: String?, currentCore: String?, currentVersion: String?,
                             forced: Boolean) {
        log("STATE_RESTORE_BEGIN", gameId, null, channel,
            "cacheId=$cacheId, slot=$slot, emulator=$emulatorId, cachedCore=$cachedCore/$cachedVersion" +
            ", currentCore=$currentCore/$currentVersion, forced=$forced")
    }

    fun logStateRestored(gameId: Long, cacheId: Long, slot: Int, channel: String?, cacheFile: String,
                         targetPath: String, screenshot: Boolean) {
        log("STATE_RESTORED", gameId, null, channel,
            "cacheId=$cacheId, slot=$slot, from=${describeFile(cacheFile)}, to=${describeFile(targetPath)}, screenshot=$screenshot")
    }

    fun logStateRestoreFailed(gameId: Long?, cacheId: Long, reason: String) {
        log("STATE_RESTORE_FAILED", gameId, null, null, "cacheId=$cacheId, reason=$reason")
    }

    /** A live state file was copied into the state cache. */
    fun logStateCached(gameId: Long, slot: Int, channel: String?, emulatorId: String, coreId: String?,
                       sourcePath: String, cachePath: String, cacheId: Long?, screenshot: Boolean) {
        log("STATE_CACHED", gameId, null, channel,
            "cacheId=$cacheId, slot=$slot, emulator=$emulatorId, core=$coreId, from=$sourcePath" +
            ", to=${describeFile(cachePath)}, screenshot=$screenshot")
    }

    /** A state came down from the server into the cache (not yet on the emulator's disk). */
    fun logStateDownloaded(gameId: Long, rommStateId: Long, fileName: String, slot: Int, channel: String?,
                           cachePath: String, contentHash: String, cacheId: Long?) {
        log("STATE_DOWNLOADED", gameId, null, channel,
            "rommStateId=$rommStateId, cacheId=$cacheId, file=$fileName, slot=$slot, to=$cachePath, md5=$contentHash")
    }

    /** A server save (or a cache hit standing in for it) landed on the emulator's disk. */
    fun logSaveLanded(gameId: Long, channel: String?, targetPath: String, serverHash: String?, viaCacheId: Long?) {
        log("SAVE_LANDED", gameId, null, channel,
            "target=${describeFile(targetPath)}, serverHash=${serverHash ?: "null"}" +
            (viaCacheId?.let { ", viaCacheId=$it" } ?: ", via=network"))
    }

    // ── The built-in core (LibretroActivity / SaveStateManager) ──

    /** Which SRAM the built-in core starts from, and the .srm it was written to. */
    fun logBuiltinSramRestore(gameId: Long?, mode: String, source: String, cacheId: Long?, channel: String?,
                              bytes: ByteArray?, targetPath: String) {
        log("BUILTIN_SRAM_RESTORE", gameId, null, channel,
            "mode=$mode, source=$source, cacheId=$cacheId, bytes=${bytes?.size ?: -1}" +
            ", md5=${bytes?.let { bytesHash(it) } ?: "null"}, target=$targetPath")
    }

    fun logBuiltinSramWrite(gameId: Long?, channel: String?, targetPath: String, bytes: Int, hash: String) {
        log("BUILTIN_SRAM_WRITE", gameId, null, channel, "bytes=$bytes, md5=$hash, target=$targetPath")
    }

    fun logBuiltinSlotLoad(gameId: Long?, slot: Int, path: String, bytes: Int, hash: String?, outcome: String) {
        log("BUILTIN_STATE_LOAD", gameId, null, null,
            "slot=$slot, file=$path, bytes=$bytes, md5=${hash ?: "?"}, outcome=$outcome")
    }

    fun logBuiltinSlotSave(gameId: Long?, slot: Int, path: String, bytes: Int, hash: String?, screenshot: Boolean) {
        log("BUILTIN_STATE_SAVE", gameId, null, null,
            "slot=$slot, file=$path, bytes=$bytes, md5=${hash ?: "?"}, screenshot=$screenshot")
    }

    /** attemptAutoRestore's decisions at launch: which file, why it was loaded, skipped or discarded. */
    fun logBuiltinAutoRestore(gameId: Long?, decision: String, path: String?, details: String? = null) {
        log("BUILTIN_AUTO_RESTORE", gameId, null, null,
            "decision=$decision" + (path?.let { ", file=${describeFile(it)}" } ?: "") + (details?.let { ", $it" } ?: ""))
    }

    private fun log(
        event: String,
        gameId: Long?,
        gameName: String?,
        channel: String?,
        details: String?
    ) {
        val message = buildString {
            append("[$event]")
            if (gameId != null) append(" gameId=$gameId")
            if (!gameName.isNullOrBlank()) append(" game=\"$gameName\"")
            if (!channel.isNullOrBlank()) append(" channel=\"$channel\"")
            if (!details.isNullOrBlank()) append(" | $details")
        }

        AndroidLog.d(TAG, message)

        if (enabled) {
            val entry = SaveLogEntry(
                timestamp = LocalDateTime.now(),
                event = event,
                gameId = gameId,
                gameName = gameName,
                channel = channel,
                details = details
            )
            scope.launch { logChannel.send(entry) }
        }
    }

    private fun writeToFile(entry: SaveLogEntry) {
        val dir = logDirectory ?: return

        val today = entry.timestamp.toLocalDate()
        if (today != currentLogDate) {
            rotateLogFile(dir, today)
        }

        val writer = fileWriter ?: return

        val line = buildString {
            append(entry.timestamp.format(timestampFormat))
            append(" [")
            append(entry.event.padEnd(20))
            append("]")
            if (entry.gameId != null) {
                append(" game=")
                append(entry.gameId)
            }
            if (!entry.gameName.isNullOrBlank()) {
                append(" \"")
                append(entry.gameName.take(30))
                append("\"")
            }
            if (!entry.channel.isNullOrBlank()) {
                append(" ch=\"")
                append(entry.channel)
                append("\"")
            }
            if (!entry.details.isNullOrBlank()) {
                append(" | ")
                append(entry.details)
            }
        }

        writer.appendLine(line)
        writer.flush()
    }

    private fun rotateLogFile(directory: String, date: LocalDate) {
        closeCurrentFile()

        val fileName = "saves-$versionName-${date.format(dateFormat)}.log"
        val logFile = File(directory, fileName)

        try {
            logFile.parentFile?.mkdirs()
            fileWriter = FileWriter(logFile, true)
            currentLogDate = date

            fileWriter?.appendLine("=== Argosy Save Debug Log - $versionName - ${date.format(dateFormat)} ===")
            fileWriter?.appendLine("Format: TIMESTAMP [EVENT] game=ID \"NAME\" ch=\"CHANNEL\" | DETAILS")
            fileWriter?.appendLine("========================================================================")
            fileWriter?.flush()
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Failed to create save debug log file", e)
            fileWriter = null
        }
    }

    private fun closeCurrentFile() {
        try {
            fileWriter?.close()
        } catch (_: Exception) { }
        fileWriter = null
        currentLogDate = null
    }

    private fun formatInstant(instant: Instant): String {
        return DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }
}

private data class SaveLogEntry(
    val timestamp: LocalDateTime,
    val event: String,
    val gameId: Long?,
    val gameName: String?,
    val channel: String?,
    val details: String?
)
