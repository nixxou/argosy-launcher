package com.nendo.argosy.data.remote.romm

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Wire shapes for LiteBox's own `/api/litebox/...` extensions — never part of the RomM contract, so
 * these fields are camelCase as LiteBox actually sends them rather than RomM's usual snake_case; no
 * official server answers this path at all (see LiteBoxService's own header for why that is safe
 * to probe for).
 */
@JsonClass(generateAdapter = true)
data class LiteBoxCapabilitiesResponse(
    val liteBox: Boolean = false,
    val features: List<String> = emptyList()
)

/** One top-level choice for a game — its own file (empty appId) or one Additional Application.
 * [eligible] is the server's own verdict, the rule its rom_id generation uses (RommFiles.CandidatesOf:
 * extractor on, archive, platform not zip-native, extraction enabled for the emulator, a mode other
 * than DoNothing): an eligible version is chosen rom by rom on a second screen, never pinned whole.
 * [romCount] is known only for an eligible version whose archive the server has already analysed.
 * [romId] is the id a whole-file version already has, null until something pins it (or when
 * eligible — only its roms carry ids). [isCurrent] says this device is served from it today. */
@JsonClass(generateAdapter = true)
data class LiteBoxVersion(
    val appId: String = "",
    val label: String = "",
    val fileName: String = "",
    val size: Long = 0L,
    val eligible: Boolean = false,
    val romCount: Int? = null,
    val romId: Long? = null,
    val isPinned: Boolean = false,
    val isCurrent: Boolean = false
)

/** One rom inside a single eligible version's archive. [path] is the archive-relative entry path,
 * opaque to Argosy — round-tripped verbatim into the pin request. The flags are the desktop
 * picker's own columns (favourite, last played, tag score, RetroAchievements match). */
@JsonClass(generateAdapter = true)
data class LiteBoxRomEntry(
    val path: String = "",
    val label: String = "",
    val fileName: String = "",
    val size: Long = 0L,
    val romId: Long? = null,
    val isPinned: Boolean = false,
    val isCurrent: Boolean = false,
    val isFavorite: Boolean = false,
    val isLastPlayed: Boolean = false,
    val score: Int = 0,
    val hasRa: Boolean = false
)

/** The second screen's payload: which version, and which archive file, the roms come from. */
@JsonClass(generateAdapter = true)
data class LiteBoxRomsInVersion(
    val appId: String = "",
    val versionLabel: String = "",
    val archiveFileName: String = "",
    val roms: List<LiteBoxRomEntry> = emptyList()
)

/** Body for POST .../pin. [unpin] alone means "follow the default again"; otherwise [appId]/[path]
 * name the choice, both blank meaning the game's own ROM as a whole. */
@JsonClass(generateAdapter = true)
data class LiteBoxPinRequest(
    val unpin: Boolean = false,
    val appId: String = "",
    val path: String = ""
)

@JsonClass(generateAdapter = true)
data class LiteBoxPinResponse(
    val ok: Boolean = false,
    val romId: Long = 0L
)

/** POST .../ra/credentials/request: the ticket to poll with, RFC 8628-style like the pairing flow. */
@JsonClass(generateAdapter = true)
data class LiteBoxRaRequestResponse(
    @Json(name = "request_id") val requestId: String = "",
    @Json(name = "expires_in") val expiresIn: Int = 300,
    val interval: Int = 3
)

/** What the desktop shares once a human approved: the RetroAchievements username and CONNECT token
 * (what login2 returns, what Argosy stores) — never a password, the server has none. */
@JsonClass(generateAdapter = true)
data class LiteBoxRaCredentials(
    val username: String = "",
    val token: String = ""
)

/** One poll of the hand-over. The server answers 400 with an RFC 8628 word in `detail` until the
 * human decides; a client branches on the exact word, hence a sealed result rather than a code. */
sealed class LiteBoxRaPoll {
    object Pending : LiteBoxRaPoll()
    object Denied : LiteBoxRaPoll()
    object Expired : LiteBoxRaPoll()
    data class Ready(val credentials: LiteBoxRaCredentials) : LiteBoxRaPoll()
    data class Failed(val message: String) : LiteBoxRaPoll()
}
