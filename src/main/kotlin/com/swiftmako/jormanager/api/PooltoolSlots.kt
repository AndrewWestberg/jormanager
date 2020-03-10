package com.swiftmako.jormanager.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PooltoolSlots(
        @Json(name = "currentepoch") val currentepoch: String,
        @Json(name = "poolid") val poolid: String,
        @Json(name = "genesispref") val genesispref: String,
        @Json(name = "userid") val userid: String,
        @Json(name = "assigned_slots") val assignedSlots: String = "0",
        @Json(name = "previous_epoch_key") val previousEpochKey: String = "",
        @Json(name = "encrypted_slots") val encryptedSlots: String
)