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
