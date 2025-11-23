package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class QueryTip(
    @param:Json(name = "era")
    val era: String?,
    @param:Json(name = "epoch")
    val epoch: Long = 0,
    @param:Json(name = "block")
    val block: Long = 0,
    @param:Json(name = "hash")
    val hash: String = "",
    @param:Json(name = "slot")
    val slot: Long = 0
) {
    val eraNumber: Int
        get() =
            when (era) {
                "Byron" -> ERA_BYRON
                "Shelley" -> ERA_SHELLEY
                "Allegra" -> ERA_ALLEGRA
                "Mary" -> ERA_MARY
                "Alonzo" -> ERA_ALONZO
                "Babbage" -> ERA_BABBAGE
                "Conway" -> ERA_CONWAY
                else -> -1
            }

    companion object {
        const val ERA_BYRON = 0
        const val ERA_SHELLEY = 1
        const val ERA_ALLEGRA = 2
        const val ERA_MARY = 3
        const val ERA_ALONZO = 4
        const val ERA_BABBAGE = 5
        const val ERA_CONWAY = 6
    }
}
