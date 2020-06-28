package com.swiftmako.jormanager.model.ekg


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Metrics(
    @Json(name = "blockNum")
    val blockNum: BlockNum = BlockNum(),
    @Json(name = "density")
    val density: Density = Density(),
    @Json(name = "epoch")
    val epoch: Epoch = Epoch(),
    @Json(name = "forksCreatedNum")
    val forksCreatedNum: ForksCreatedNum = ForksCreatedNum(),
    @Json(name = "slotInEpoch")
    val slotInEpoch: SlotInEpoch = SlotInEpoch(),
    @Json(name = "slotNum")
    val slotNum: SlotNum = SlotNum()
)