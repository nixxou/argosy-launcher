package com.nendo.argosy.data.remote.romm

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

/** One top-level choice for a game — its own ROM ("main", empty appId) or one Additional
 * Application. [mayHaveRoms] is cheap (no archive opened) and only says "worth a drill-down tap" —
 * the real answer is whatever LiteBoxRomsInVersion returns once the user actually asks. */
@JsonClass(generateAdapter = true)
data class LiteBoxVersion(
    val appId: String = "",
    val label: String = "",
    val fileName: String = "",
    val size: Long = 0L,
    val isPinned: Boolean = false,
    val mayHaveRoms: Boolean = false
)

/** One file inside a single version's own archive. [path] is the archive-relative entry path, opaque
 * to Argosy — round-tripped verbatim into the pin request. */
@JsonClass(generateAdapter = true)
data class LiteBoxRomEntry(
    val path: String = "",
    val label: String = "",
    val fileName: String = "",
    val size: Long = 0L,
    val isPinned: Boolean = false
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
