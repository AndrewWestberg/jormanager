package com.swiftmako.jormanager.api

import com.fasterxml.jackson.annotation.JsonProperty
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
    abstract val processId: Int
}

@JsonClass(generateAdapter = false)
data class PendingBlock(
        @JsonProperty("created_at_time")
        @Json(name = "created_at_time") override val createdAtTime: String,
        @JsonProperty("enclave_leader_id")
        @Json(name = "enclave_leader_id") override val enclaveLeaderId: Int,
        @JsonProperty("finished_at_time")
        @Json(name = "finished_at_time") override val finishedAtTime: String?,
        @JsonProperty("scheduled_at_date")
        @Json(name = "scheduled_at_date") override val scheduledAtDate: String,
        @JsonProperty("scheduled_at_time")
        @Json(name = "scheduled_at_time") override val scheduledAtTime: String,
        @JsonProperty("wake_at_time")
        @Json(name = "wake_at_time") override val wakeAtTime: String?,
        @JsonProperty("minted")
        @Json(name = "minted") override val minted: Boolean?,
        @JsonProperty("processId")
        @Json(name = "processId") override val processId: Int = -1,
        @JsonProperty("status")
        @Json(name = "status") val status: String
) : LeaderBlock()

@JsonClass(generateAdapter = false)
data class CompletedBlock(
        @JsonProperty("created_at_time")
        @Json(name = "created_at_time") override val createdAtTime: String,
        @JsonProperty("enclave_leader_id")
        @Json(name = "enclave_leader_id") override val enclaveLeaderId: Int,
        @JsonProperty("finished_at_time")
        @Json(name = "finished_at_time") override val finishedAtTime: String?,
        @JsonProperty("scheduled_at_date")
        @Json(name = "scheduled_at_date") override val scheduledAtDate: String,
        @JsonProperty("scheduled_at_time")
        @Json(name = "scheduled_at_time") override val scheduledAtTime: String,
        @JsonProperty("wake_at_time")
        @Json(name = "wake_at_time") override val wakeAtTime: String?,
        @JsonProperty("minted")
        @Json(name = "minted") override val minted: Boolean?,
        @JsonProperty("processId")
        @Json(name = "processId") override val processId: Int = -1,
        @JsonProperty("status")
        @Json(name = "status") val status: BlockStatus
) : LeaderBlock()

@JsonClass(generateAdapter = false)
data class RejectedBlock(
        @JsonProperty("created_at_time")
        @Json(name = "created_at_time") override val createdAtTime: String,
        @JsonProperty("enclave_leader_id")
        @Json(name = "enclave_leader_id") override val enclaveLeaderId: Int,
        @JsonProperty("finished_at_time")
        @Json(name = "finished_at_time") override val finishedAtTime: String?,
        @JsonProperty("scheduled_at_date")
        @Json(name = "scheduled_at_date") override val scheduledAtDate: String,
        @JsonProperty("scheduled_at_time")
        @Json(name = "scheduled_at_time") override val scheduledAtTime: String,
        @JsonProperty("wake_at_time")
        @Json(name = "wake_at_time") override val wakeAtTime: String?,
        @JsonProperty("minted")
        @Json(name = "minted") override val minted: Boolean?,
        @JsonProperty("processId")
        @Json(name = "processId") override val processId: Int = -1,
        @JsonProperty("status")
        @Json(name = "status") val status: RejectedStatus
) : LeaderBlock()
