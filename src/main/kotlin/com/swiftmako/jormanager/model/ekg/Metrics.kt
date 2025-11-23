package com.swiftmako.jormanager.model.ekg

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Metrics(
    @param:Json(name = "blockNum")
    val blockNum: BlockNum = BlockNum(),
    @param:Json(name = "density")
    val density: Density = Density(),
    @param:Json(name = "epoch")
    val epoch: Epoch = Epoch(),
    @param:Json(name = "forksCreatedNum")
    val forksCreatedNum: ForksCreatedNum = ForksCreatedNum(),
    @param:Json(name = "slotInEpoch")
    val slotInEpoch: SlotInEpoch = SlotInEpoch(),
    @param:Json(name = "slotNum")
    val slotNum: SlotNum = SlotNum()
)
