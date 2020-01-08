package com.swiftmako.jormanager.api

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.internal.Util
import java.io.IOException
import kotlin.Boolean
import kotlin.Int
import kotlin.String

@Suppress("DEPRECATION", "unused", "ClassName", "REDUNDANT_PROJECTION")
class LeaderBlockJsonAdapter(
        moshi: Moshi
) : JsonAdapter<LeaderBlock>() {
    private val options: JsonReader.Options = JsonReader.Options.of("created_at_time",
            "enclave_leader_id", "finished_at_time", "scheduled_at_date", "scheduled_at_time",
            "wake_at_time", "status", "minted")

    private val statusOptions: JsonReader.Options = JsonReader.Options.of("Block", "Rejected")

    private val stringAdapter: JsonAdapter<String> = moshi.adapter(String::class.java, emptySet(),
            "createdAtTime")

    private val intAdapter: JsonAdapter<Int> = moshi.adapter(Int::class.java, emptySet(),
            "enclaveLeaderId")

    private val nullableStringAdapter: JsonAdapter<String?> = moshi.adapter(String::class.java,
            emptySet(), "finishedAtTime")

    private val blockStatusJsonAdapter: JsonAdapter<BlockStatus> = moshi.adapter(BlockStatus::class.java,
            emptySet(), "status")

    private val rejectedStatusJsonAdapter: JsonAdapter<RejectedStatus> = moshi.adapter(RejectedStatus::class.java,
            emptySet(), "status")

    private val nullableBooleanAdapter: JsonAdapter<Boolean?> =
            moshi.adapter(Boolean::class.javaObjectType, emptySet(), "minted")

    override fun toString(): String = buildString(27) {
        append("GeneratedJsonAdapter(").append("Block").append(')')
    }

    override fun fromJson(reader: JsonReader): LeaderBlock {
        var createdAtTime: String? = null
        var enclaveLeaderId: Int? = null
        var finishedAtTime: String? = null
        var scheduledAtDate: String? = null
        var scheduledAtTime: String? = null
        var wakeAtTime: String? = null
        var statusString: String? = null
        var statusBlock: BlockStatus? = null
        var statusRejected: RejectedStatus? = null
        var minted: Boolean? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.selectName(options)) {
                0 -> createdAtTime = stringAdapter.fromJson(reader)
                        ?: throw Util.unexpectedNull("createdAtTime", "created_at_time", reader)
                1 -> enclaveLeaderId = intAdapter.fromJson(reader)
                        ?: throw Util.unexpectedNull("enclaveLeaderId", "enclave_leader_id", reader)
                2 -> finishedAtTime = nullableStringAdapter.fromJson(reader)
                3 -> scheduledAtDate = stringAdapter.fromJson(reader)
                        ?: throw Util.unexpectedNull("scheduledAtDate", "scheduled_at_date", reader)
                4 -> scheduledAtTime = stringAdapter.fromJson(reader)
                        ?: throw Util.unexpectedNull("scheduledAtTime", "scheduled_at_time", reader)
                5 -> wakeAtTime = nullableStringAdapter.fromJson(reader)
                6 -> {
                    when(reader.peek()) {
                        JsonReader.Token.STRING -> statusString = stringAdapter.fromJson(reader)
                                ?: throw Util.unexpectedNull("status", "status", reader)
                        JsonReader.Token.BEGIN_OBJECT -> {
                            val lookAheadReader = reader.peekJson()
                            lookAheadReader.beginObject()
                            when(lookAheadReader.selectName(statusOptions)) {
                                0 -> statusBlock = blockStatusJsonAdapter.fromJson(reader)
                                        ?: throw Util.unexpectedNull("status", "status", reader)
                                1-> statusRejected = rejectedStatusJsonAdapter.fromJson(reader)
                                        ?: throw Util.unexpectedNull("status", "status", reader)
                            }
                        }
                        else -> {
                            throw JsonDataException("Expected status to be a String or an object!")
                        }
                    }
                }
                7 -> minted = nullableBooleanAdapter.fromJson(reader)
                -1 -> {
                    // Unknown name, skip it.
                    reader.skipName()
                    reader.skipValue()
                }
            }
        }
        reader.endObject()

        return when {
            statusString != null -> PendingBlock(
                    createdAtTime = createdAtTime ?: throw Util.missingProperty("createdAtTime",
                            "created_at_time", reader),
                    enclaveLeaderId = enclaveLeaderId ?: throw Util.missingProperty("enclaveLeaderId",
                            "enclave_leader_id", reader),
                    finishedAtTime = finishedAtTime,
                    scheduledAtDate = scheduledAtDate ?: throw Util.missingProperty("scheduledAtDate",
                            "scheduled_at_date", reader),
                    scheduledAtTime = scheduledAtTime ?: throw Util.missingProperty("scheduledAtTime",
                            "scheduled_at_time", reader),
                    wakeAtTime = wakeAtTime,
                    status = statusString,
                    minted = minted
            )
            statusBlock != null -> CompletedBlock(
                    createdAtTime = createdAtTime ?: throw Util.missingProperty("createdAtTime",
                            "created_at_time", reader),
                    enclaveLeaderId = enclaveLeaderId ?: throw Util.missingProperty("enclaveLeaderId",
                            "enclave_leader_id", reader),
                    finishedAtTime = finishedAtTime,
                    scheduledAtDate = scheduledAtDate ?: throw Util.missingProperty("scheduledAtDate",
                            "scheduled_at_date", reader),
                    scheduledAtTime = scheduledAtTime ?: throw Util.missingProperty("scheduledAtTime",
                            "scheduled_at_time", reader),
                    wakeAtTime = wakeAtTime,
                    status = statusBlock,
                    minted = minted
            )
            else -> RejectedBlock(
                    createdAtTime = createdAtTime ?: throw Util.missingProperty("createdAtTime",
                            "created_at_time", reader),
                    enclaveLeaderId = enclaveLeaderId ?: throw Util.missingProperty("enclaveLeaderId",
                            "enclave_leader_id", reader),
                    finishedAtTime = finishedAtTime,
                    scheduledAtDate = scheduledAtDate ?: throw Util.missingProperty("scheduledAtDate",
                            "scheduled_at_date", reader),
                    scheduledAtTime = scheduledAtTime ?: throw Util.missingProperty("scheduledAtTime",
                            "scheduled_at_time", reader),
                    wakeAtTime = wakeAtTime,
                    status = statusRejected ?: throw Util.missingProperty("status", "status", reader),
                    minted = minted
            )
        }
    }

    override fun toJson(writer: JsonWriter, value: LeaderBlock?) {
        if (value == null) {
            throw NullPointerException("value was null! Wrap in .nullSafe() to write nullable values.")
        }
        when (value) {
            is PendingBlock -> {
                writer.beginObject()
                writer.name("created_at_time")
                stringAdapter.toJson(writer, value.createdAtTime)
                writer.name("enclave_leader_id")
                intAdapter.toJson(writer, value.enclaveLeaderId)
                writer.name("finished_at_time")
                nullableStringAdapter.toJson(writer, value.finishedAtTime)
                writer.name("scheduled_at_date")
                stringAdapter.toJson(writer, value.scheduledAtDate)
                writer.name("scheduled_at_time")
                stringAdapter.toJson(writer, value.scheduledAtTime)
                writer.name("wake_at_time")
                nullableStringAdapter.toJson(writer, value.wakeAtTime)
                writer.name("status")
                stringAdapter.toJson(writer, value.status)
                writer.name("minted")
                nullableBooleanAdapter.toJson(writer, value.minted)
                writer.endObject()
            }
            is CompletedBlock -> {
                writer.beginObject()
                writer.name("created_at_time")
                stringAdapter.toJson(writer, value.createdAtTime)
                writer.name("enclave_leader_id")
                intAdapter.toJson(writer, value.enclaveLeaderId)
                writer.name("finished_at_time")
                nullableStringAdapter.toJson(writer, value.finishedAtTime)
                writer.name("scheduled_at_date")
                stringAdapter.toJson(writer, value.scheduledAtDate)
                writer.name("scheduled_at_time")
                stringAdapter.toJson(writer, value.scheduledAtTime)
                writer.name("wake_at_time")
                nullableStringAdapter.toJson(writer, value.wakeAtTime)
                writer.name("status")
                blockStatusJsonAdapter.toJson(writer, value.status)
                writer.name("minted")
                nullableBooleanAdapter.toJson(writer, value.minted)
                writer.endObject()
            }
            is RejectedBlock -> {
                writer.beginObject()
                writer.name("created_at_time")
                stringAdapter.toJson(writer, value.createdAtTime)
                writer.name("enclave_leader_id")
                intAdapter.toJson(writer, value.enclaveLeaderId)
                writer.name("finished_at_time")
                nullableStringAdapter.toJson(writer, value.finishedAtTime)
                writer.name("scheduled_at_date")
                stringAdapter.toJson(writer, value.scheduledAtDate)
                writer.name("scheduled_at_time")
                stringAdapter.toJson(writer, value.scheduledAtTime)
                writer.name("wake_at_time")
                nullableStringAdapter.toJson(writer, value.wakeAtTime)
                writer.name("status")
                rejectedStatusJsonAdapter.toJson(writer, value.status)
                writer.name("minted")
                nullableBooleanAdapter.toJson(writer, value.minted)
                writer.endObject()
            }
        }
    }
}
