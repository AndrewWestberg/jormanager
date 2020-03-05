package com.swiftmako.jormanager.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PooltoolMessage(
        @Json(name = "assigned_slots_saved") val assignedSlotsSaved: String?,
        @Json(name = "encrypted_slots_saved") val encryptedSlotsSaved: Boolean?,
        @Json(name = "error") val error: String?
)