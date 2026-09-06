package com.nendo.argosy.data.remote.romm

import com.nendo.argosy.util.Logger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LiteBoxService"

/** The path segment that names a game's own ROM in the versions/roms path — a path segment cannot be
 * empty, so the server (RommLiteBoxApi.cs) treats this literal string as "" internally. */
const val LITEBOX_MAIN_VERSION = "main"

/**
 * LiteBox's own `/api/litebox/...` extensions to the RomM contract — version switching
 * (RommIndexer.PinClient/UnpinClient on the server, exposed for a client instead of only the desktop
 * Assignment screen). No official RomM server answers any of these paths, so a plain 404/501/network
 * error here is read as "this feature does not exist on this server", never surfaced as a failure the
 * rest of the app should react to — capabilities() is the one call worth probing explicitly; the
 * others are only ever made after that probe already said yes.
 */
@Singleton
class LiteBoxService @Inject constructor(
    private val connectionManager: RomMConnectionManager
) {
    private val api: RomMApi? get() = connectionManager.getApi()

    /** Cached per SERVER: keyed on the RomMApi instance, which RomMConnectionManager rebuilds for a
     * new connection — so a re-pair to a different server re-probes instead of inheriting the old
     * answer. Only a real HTTP answer is kept (200 = LiteBox, 404/501 = an official server saying
     * no); a network failure is never cached, otherwise the first Game Detail opened while offline
     * would hide the feature for the rest of the process (the first version of this did exactly
     * that). */
    @Volatile
    private var cached: Pair<RomMApi, LiteBoxCapabilitiesResponse>? = null

    fun supportsVersionSwitch(): Boolean {
        val client = api ?: return false
        val c = cached ?: return false
        return c.first === client && c.second.features.contains("version-switch")
    }

    suspend fun capabilities(): LiteBoxCapabilitiesResponse {
        val client = api ?: return LiteBoxCapabilitiesResponse()
        cached?.let { if (it.first === client) return it.second }
        val response = try {
            client.getLiteBoxCapabilities()
        } catch (e: Exception) {
            Logger.debug(TAG, "capabilities probe failed (offline?), not cached: ${e.message}")
            return LiteBoxCapabilitiesResponse()
        }
        val result = if (response.isSuccessful) {
            response.body() ?: LiteBoxCapabilitiesResponse()
        } else {
            LiteBoxCapabilitiesResponse()
        }
        cached = client to result
        return result
    }

    suspend fun listVersions(romId: Long): RomMResult<List<LiteBoxVersion>> {
        val client = api ?: return RomMResult.Error("Not connected")
        return try {
            val response = client.getLiteBoxVersions(romId)
            if (response.isSuccessful) RomMResult.Success(response.body() ?: emptyList())
            else RomMResult.Error("Server returned ${response.code()}", code = response.code())
        } catch (e: Exception) {
            RomMResult.Error(e.message ?: "Connection failed")
        }
    }

    suspend fun listRomsInVersion(romId: Long, appId: String?): RomMResult<List<LiteBoxRomEntry>> {
        val client = api ?: return RomMResult.Error("Not connected")
        val segment = appId?.takeIf { it.isNotBlank() } ?: LITEBOX_MAIN_VERSION
        return try {
            val response = client.getLiteBoxRomsInVersion(romId, segment)
            if (response.isSuccessful) RomMResult.Success(response.body() ?: emptyList())
            else RomMResult.Error("Server returned ${response.code()}", code = response.code())
        } catch (e: Exception) {
            RomMResult.Error(e.message ?: "Connection failed")
        }
    }

    /** Locks this device onto one specific file. Both blank means the game's own ROM as a whole. */
    suspend fun pinVersion(romId: Long, appId: String?, path: String?): RomMResult<LiteBoxPinResponse> {
        val client = api ?: return RomMResult.Error("Not connected")
        return try {
            val response = client.pinLiteBoxVersion(
                romId,
                LiteBoxPinRequest(unpin = false, appId = appId.orEmpty(), path = path.orEmpty())
            )
            if (response.isSuccessful) RomMResult.Success(response.body() ?: LiteBoxPinResponse())
            else RomMResult.Error("Server returned ${response.code()}", code = response.code())
        } catch (e: Exception) {
            RomMResult.Error(e.message ?: "Connection failed")
        }
    }

    /** Releases this device back to the game's own default — the ranking the server already computes,
     * for ever, rather than whatever it happens to be right now. */
    suspend fun unpinVersion(romId: Long): RomMResult<LiteBoxPinResponse> {
        val client = api ?: return RomMResult.Error("Not connected")
        return try {
            val response = client.pinLiteBoxVersion(romId, LiteBoxPinRequest(unpin = true))
            if (response.isSuccessful) RomMResult.Success(response.body() ?: LiteBoxPinResponse())
            else RomMResult.Error("Server returned ${response.code()}", code = response.code())
        } catch (e: Exception) {
            RomMResult.Error(e.message ?: "Connection failed")
        }
    }
}
