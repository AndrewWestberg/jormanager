package com.swiftmako.jormanager.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true, generator = "skipMe")
sealed class LeaderBlock {
    abstract val createdAtTime: String
    abstract val enclaveLeaderId: Int
    abstract val finishedAtTime: String?
    abstract val scheduledAtDate: String
    abstract val scheduledAtTime: String
    abstract val wakeAtTime: String?
    abstract val minted: Boolean?
}

@JsonClass(generateAdapter = false)
data class PendingBlock(
        @Json(name = "created_at_time") override val createdAtTime: String,
        @Json(name = "enclave_leader_id") override val enclaveLeaderId: Int,
        @Json(name = "finished_at_time") override val finishedAtTime: String?,
        @Json(name = "scheduled_at_date") override val scheduledAtDate: String,
        @Json(name = "scheduled_at_time") override val scheduledAtTime: String,
        @Json(name = "wake_at_time") override val wakeAtTime: String?,
        @Json(name = "minted") override val minted: Boolean?,
        @Json(name = "status") val status: String
) : LeaderBlock()

@JsonClass(generateAdapter = false)
data class CompletedBlock(
        @Json(name = "created_at_time") override val createdAtTime: String,
        @Json(name = "enclave_leader_id") override val enclaveLeaderId: Int,
        @Json(name = "finished_at_time") override val finishedAtTime: String?,
        @Json(name = "scheduled_at_date") override val scheduledAtDate: String,
        @Json(name = "scheduled_at_time") override val scheduledAtTime: String,
        @Json(name = "wake_at_time") override val wakeAtTime: String?,
        @Json(name = "minted") override val minted: Boolean?,
        @Json(name = "status") val status: BlockStatus
) : LeaderBlock()

@JsonClass(generateAdapter = false)
data class RejectedBlock(
        @Json(name = "created_at_time") override val createdAtTime: String,
        @Json(name = "enclave_leader_id") override val enclaveLeaderId: Int,
        @Json(name = "finished_at_time") override val finishedAtTime: String?,
        @Json(name = "scheduled_at_date") override val scheduledAtDate: String,
        @Json(name = "scheduled_at_time") override val scheduledAtTime: String,
        @Json(name = "wake_at_time") override val wakeAtTime: String?,
        @Json(name = "minted") override val minted: Boolean?,
        @Json(name = "status") val status: RejectedStatus
) : LeaderBlock()
