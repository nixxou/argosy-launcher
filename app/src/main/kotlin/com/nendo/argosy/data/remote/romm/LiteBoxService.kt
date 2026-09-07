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
}
