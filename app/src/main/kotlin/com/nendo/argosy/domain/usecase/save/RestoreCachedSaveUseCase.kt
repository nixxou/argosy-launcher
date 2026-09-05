package com.nendo.argosy.domain.usecase.save

import android.util.Log
import com.nendo.argosy.data.emulator.EmulatorResolver
import com.nendo.argosy.data.local.dao.GameDao
import com.nendo.argosy.data.repository.SaveCacheManager
import com.nendo.argosy.data.repository.SaveSyncRepository
import com.nendo.argosy.data.repository.SaveSyncResult
import com.nendo.argosy.data.repository.StateCacheManager
import com.nendo.argosy.domain.model.UnifiedSaveEntry
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Why [RestoreCachedSaveUseCase] could not put a cached or server save back into play.
 */
sealed class RestoreCachedSaveFailureReason {
    data object GameNotFound : RestoreCachedSaveFailureReason()
    data object NoLocalCopy : RestoreCachedSaveFailureReason()
    data object SaveLocationUnresolved : RestoreCachedSaveFailureReason()
    data object ClearExistingSaveFailed : RestoreCachedSaveFailureReason()
    data object NoLocalCacheId : RestoreCachedSaveFailureReason()
    data object NoServerSaveId : RestoreCachedSaveFailureReason()
    data object RestoreFailed : RestoreCachedSaveFailureReason()
}

class RestoreCachedSaveUseCase @Inject constructor(
    private val saveCacheManager: SaveCacheManager,
    private val saveSyncRepository: SaveSyncRepository,
    private val gameDao: GameDao,
    private val activeSaveRepository: com.nendo.argosy.data.repository.ActiveSaveRepository,
    private val emulatorResolver: EmulatorResolver,
    private val stateCacheManager: StateCacheManager,
    private val preferencesRepository: com.nendo.argosy.data.preferences.UserPreferencesRepository
) {
    private val TAG = "RestoreCachedSaveUseCase"

    sealed class Result {
        data object Restored : Result()
        data object RestoredAndSynced : Result()
        data class Error(val reason: RestoreCachedSaveFailureReason) : Result()
    }

    suspend operator fun invoke(
        entry: UnifiedSaveEntry,
        gameId: Long,
        emulatorId: String,
        syncToServer: Boolean
    ): Result {
        val game = gameDao.getById(gameId)
            ?: return Result.Error(RestoreCachedSaveFailureReason.GameNotFound)
        if (game.localPath == null) {
            Log.d(TAG, "Skipping restore: game $gameId has no local ROM")
            return Result.Error(RestoreCachedSaveFailureReason.NoLocalCopy)
        }

        val emulatorPackage = emulatorResolver.getEmulatorPackageForGame(gameId, game.platformId, game.platformSlug)
        val coreName = saveSyncRepository.resolveCoreForGame(gameId)

        val targetPath = saveSyncRepository.discoverSavePath(
            emulatorId = emulatorId,
            gameTitle = game.title,
            platformSlug = game.platformSlug,
            romPath = game.localPath,
            cachedSaveId = game.saveId ?: game.titleId,
            coreName = coreName,
            emulatorPackage = emulatorPackage,
            gameId = gameId
        ) ?: saveSyncRepository.constructSavePath(
            emulatorId, game.title, game.platformSlug, game.localPath, coreName, game.saveId ?: game.titleId, gameId,
            folderShaped = entry.serverFileName?.endsWith(".zip", ignoreCase = true)
        ) ?: return Result.Error(RestoreCachedSaveFailureReason.SaveLocationUnresolved)

        val archiveRoots = when (entry.source) {
            UnifiedSaveEntry.Source.LOCAL,
            UnifiedSaveEntry.Source.BOTH -> entry.localCacheId?.let { saveCacheManager.archiveRootNames(it) }
            UnifiedSaveEntry.Source.SERVER -> null
        }
        // Captured before the clear below overwrites it -- the only way to know afterward whether
        // this restore actually CHANGED the content, versus re-landing the same bytes that are
        // already active (a no-op that must not go nuking the built-in core's own resume state).
        val previousHash = saveCacheManager.calculateLocalSaveHash(targetPath)
        if (!saveSyncRepository.clearSavesBeforeRestore(targetPath, game.platformSlug, game.saveId ?: game.titleId, archiveRoots)) {
            return Result.Error(RestoreCachedSaveFailureReason.ClearExistingSaveFailed)
        }

        var cachedHash: String? = null
        val restoreSuccess = when (entry.source) {
            UnifiedSaveEntry.Source.LOCAL,
            UnifiedSaveEntry.Source.BOTH -> {
                val cacheId = entry.localCacheId
                    ?: return Result.Error(RestoreCachedSaveFailureReason.NoLocalCacheId)
                cachedHash = saveCacheManager.getCacheById(cacheId)?.contentHash
                saveCacheManager.restoreSave(cacheId, targetPath)
            }
            UnifiedSaveEntry.Source.SERVER -> {
                val serverSaveId = entry.serverSaveId
                    ?: return Result.Error(RestoreCachedSaveFailureReason.NoServerSaveId)
                val downloaded = saveSyncRepository.downloadSaveById(
                    serverSaveId = serverSaveId,
                    targetPath = targetPath,
                    emulatorId = emulatorId,
                    emulatorPackage = emulatorPackage,
                    gameId = gameId,
                    romPath = game.localPath
                )
                // Also persist a local cache entry (tagged with rommSaveId) so after the restore the
                // unified view sees this save as BOTH rather than SERVER-only -- otherwise cache-only
                // readers and the next active-save resolution misread it. downloadSaveById stays the
                // live restore because it is layout-aware (GCI/Switch/folder); caching separately
                // reuses the proven server-download cache path instead of duplicating that layout logic.
                // Opportunistic, not part of the restore contract: the save is already on disk, so a
                // failed cache write only degrades the unified view back to server-only until the
                // next sync. Log and carry on -- reporting Error here would tell the user a restore
                // that succeeded had failed.
                if (downloaded &&
                    !saveSyncRepository.downloadAndCacheSave(serverSaveId, gameId, entry.channelName)
                ) {
                    Log.w(TAG, "Restored server save $serverSaveId but failed to cache it locally; unified view stays server-only until the next sync")
                }
                downloaded
            }
        }

        if (!restoreSuccess) {
            return Result.Error(RestoreCachedSaveFailureReason.RestoreFailed)
        }

        val restoredContentHash = when (entry.source) {
            UnifiedSaveEntry.Source.LOCAL,
            UnifiedSaveEntry.Source.BOTH -> cachedHash ?: saveCacheManager.calculateLocalSaveHash(targetPath)
            UnifiedSaveEntry.Source.SERVER -> null
        }

        // The built-in core's own AUTO_SLOT/RESUME_SLOT describe a moment that assumed the PREVIOUS
        // save -- a restore that actually changed the content invalidates that assumption the same
        // way a server pull does (see SaveDownloader.downloadSave and LibretroActivity's own launch-
        // time check). Skipped when the hash could not be read on either side: silence must not read
        // as "definitely different" and wipe a resume state a plain no-op restore should have left
        // alone.
        if (emulatorId == "builtin" &&
            preferencesRepository.userPreferences.first().protectAgainstStaleResume &&
            previousHash != null && restoredContentHash != null && previousHash != restoredContentHash
        ) {
            stateCacheManager.deleteAutoResumeStatesFromDisk(
                emulatorId = emulatorId,
                romPath = game.localPath,
                platformSlug = game.platformSlug,
                emulatorPackage = emulatorPackage,
                coreId = coreName,
                gameId = gameId
            )
        }

        val targetChannel = entry.channelName
            ?: com.nendo.argosy.data.repository.SaveSyncApiClient.AUTOSAVE_SLOT_NAME
        val restoredCacheId = entry.localCacheId
        if (restoredCacheId != null) {
            activeSaveRepository.activateCache(gameId, restoredCacheId)
        } else {
            activeSaveRepository.activateChannel(gameId, entry.channelName)
        }

        if (game.rommId != null) {
            saveSyncRepository.markRestored(
                gameId = gameId,
                rommId = game.rommId,
                emulatorId = emulatorId,
                channelName = targetChannel,
                localPath = targetPath,
                rommSaveId = entry.serverSaveId,
                serverTimestamp = entry.timestamp,
                contentHash = restoredContentHash
            )
        }

        if (entry.serverSaveId != null) {
            activeSaveRepository.setPendingDeviceSyncSaveId(gameId, entry.serverSaveId)
            try {
                saveSyncRepository.confirmDeviceSynced(entry.serverSaveId)
                activeSaveRepository.setPendingDeviceSyncSaveId(gameId, null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to confirm device sync for saveId=${entry.serverSaveId}, will retry before next sync", e)
            }
        }

        if (syncToServer && game.rommId != null) {
            return when (val uploadResult = saveSyncRepository.uploadSave(gameId, emulatorId, targetChannel)) {
                is SaveSyncResult.Success -> Result.RestoredAndSynced
                is SaveSyncResult.Error -> {
                    Log.w(TAG, "Restored but failed to sync: ${uploadResult.message}")
                    Result.Restored
                }
                else -> Result.Restored
            }
        }

        return Result.Restored
    }

    suspend fun clearActiveSave(gameId: Long, emulatorId: String): Boolean {
        val game = gameDao.getById(gameId) ?: return true
        if (game.localPath == null) return true
        val emulatorPackage = emulatorResolver.getEmulatorPackageForGame(
            gameId, game.platformId, game.platformSlug
        )
        val coreName = saveSyncRepository.resolveCoreForGame(gameId)
        val targetPath = saveSyncRepository.discoverSavePath(
            emulatorId = emulatorId,
            gameTitle = game.title,
            platformSlug = game.platformSlug,
            romPath = game.localPath,
            cachedSaveId = game.saveId ?: game.titleId,
            coreName = coreName,
            emulatorPackage = emulatorPackage,
            gameId = gameId
        ) ?: return true
        return saveSyncRepository.clearSavesForTitle(targetPath, game.platformSlug, game.saveId ?: game.titleId)
    }
}
