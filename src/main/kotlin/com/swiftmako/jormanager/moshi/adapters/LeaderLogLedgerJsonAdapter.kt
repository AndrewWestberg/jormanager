package com.swiftmako.jormanager.moshi.adapters

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.internal.Util
import com.swiftmako.jormanager.ktx.sumByLong
import com.swiftmako.jormanager.model.LeaderLogLedger
import java.math.BigDecimal
import java.math.RoundingMode

class LeaderLogLedgerJsonAdapter(moshi: Moshi, private val poolIds: Set<String>) : JsonAdapter<LeaderLogLedger>() {

    private val doubleAdapter: JsonAdapter<Double> = moshi.adapter(Double::class.java, emptySet(), "decentralisationParam")

    private val options: List<JsonReader.Options> = listOf(
            JsonReader.Options.of("nesEs", "esPp", "esSnapshots", "esLState"),
            JsonReader.Options.of("decentralisationParam"),
            JsonReader.Options.of("_pstakeSet", "_pstakeMark"), //_pstakeSet is current epoch, _pstakeMark is future epoch
            JsonReader.Options.of("_stake", "_delegations"),
            JsonReader.Options.of("_utxoState"),
            JsonReader.Options.of("_ppups"),
            JsonReader.Options.of("proposals"),
            JsonReader.Options.of("_d"),
    )

    override fun fromJson(reader: JsonReader): LeaderLogLedger? {
        var decentralizationParameter = -1.0
        var futureDecentralizationParameter = -1.0
        val poolIdToSigma = mutableMapOf<String, BigDecimal>()
        val futurePoolIdToSigma = mutableMapOf<String, BigDecimal>()
        var dProposalVotes = 0
        var isLedgerV2 = false

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.selectName(options[0])) {
                0 -> {
                    //nesEs
                    reader.beginObject()
                    isLedgerV2 = true
                    continue
                }
                1 -> {
                    // esPp
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.selectName(options[1])) {
                            0 -> {
                                // decentralisationParam
                                decentralizationParameter = doubleAdapter.fromJson(reader)
                                        ?: throw Util.unexpectedNull("decentralisationParam", "decentralisationParam", reader)
                            }
                            -1 -> {
                                reader.skipName()
                                reader.skipValue()
                            }
                        }
                    }
                    reader.endObject()
                }
                2 -> {
                    // esSnapshots
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.selectName(options[2])) {
                            0 -> {
                                // _pstakeSet
                                calculateSigmaValues(reader, poolIdToSigma)
                            }
                            1 -> {
                                // _pstakeMark
                                calculateSigmaValues(reader, futurePoolIdToSigma)
                            }
                            -1 -> {
                                reader.skipName()
                                reader.skipValue()
                            }
                        }
                    }
                    reader.endObject()

                }
                3 -> {
                    // esLState
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.selectName(options[4])) {
                            0 -> {
                                // _utxoState
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.selectName(options[5])) {
                                        0 -> {
                                            // _ppups
                                            reader.beginObject()
                                            while (reader.hasNext()) {
                                                when (reader.selectName(options[6])) {
                                                    0 -> {
                                                        // proposals
                                                        reader.beginObject()
                                                        while (reader.hasNext()) {
                                                            reader.nextName() // consume the voter id name
                                                            reader.beginObject()
                                                            while (reader.hasNext()) {
                                                                when (reader.selectName(options[7])) {
                                                                    0 -> {
                                                                        // _d
                                                                        if (reader.peek() == JsonReader.Token.NULL) {
                                                                            reader.skipValue()
                                                                        } else {
                                                                            dProposalVotes++
                                                                            if (dProposalVotes == 5) {
                                                                                futureDecentralizationParameter = reader.nextDouble()
                                                                            } else {
                                                                                reader.skipValue()
                                                                            }
                                                                        }
                                                                    }
                                                                    -1 -> {
                                                                        reader.skipName()
                                                                        reader.skipValue()
                                                                    }
                                                                }
                                                            }
                                                            reader.endObject()
                                                        }
                                                        reader.endObject()
                                                    }
                                                    -1 -> {
                                                        reader.skipName()
                                                        reader.skipValue()
                                                    }
                                                }

                                            }
                                            reader.endObject()
                                        }
                                        -1 -> {
                                            reader.skipName()
                                            reader.skipValue()
                                        }
                                    }
                                }
                                reader.endObject()
                            }
                            -1 -> {
                                reader.skipName()
                                reader.skipValue()
                            }
                        }
                    }
                    reader.endObject()
                }
                -1 -> {
                    reader.skipName()
                    reader.skipValue()
                }
            }
        }
        reader.endObject()
        if (isLedgerV2) {
            while (reader.hasNext()) {
                reader.skipName()
                reader.skipValue()
            }
            reader.endObject()
        }

        // There is no proposal to update d that we found.
        if (futureDecentralizationParameter < 0.0) {
            futureDecentralizationParameter = decentralizationParameter
        }

        return LeaderLogLedger(
                decentralizationParameter = decentralizationParameter,
                futureDecentralizationParameter = futureDecentralizationParameter,
                poolIdToSigma = poolIdToSigma,
                futurePoolIdToSigma = futurePoolIdToSigma,
        )
    }

    private fun calculateSigmaValues(reader: JsonReader, poolIdToSigma: MutableMap<String, BigDecimal>) {
        val stakeKeyToValue = mutableMapOf<String, Long>()
        val stakeKeyToPoolId = mutableMapOf<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.selectName(options[3])) {
                0 -> {
                    // _stake
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginArray()
                        reader.beginObject()
                        reader.skipName() // skip the name which is "key hash"
                        val stakeKey = reader.nextString()
                        reader.endObject()
                        val stakeValue = reader.nextLong()
                        if (stakeValue > 0) {
                            stakeKeyToValue[stakeKey] = stakeValue
                        }
                        reader.endArray()
                    }
                    reader.endArray()
                }
                1 -> {
                    // _delegations
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginArray()
                        reader.beginObject()
                        reader.skipName() // skip the name which is "key hash"
                        val stakeKey = reader.nextString()
                        reader.endObject()
                        val poolId = reader.nextString()
                        stakeKeyToPoolId[stakeKey] = poolId
                        reader.endArray()
                    }
                    reader.endArray()
                }
                -1 -> {
                    reader.skipName()
                    reader.skipValue()
                }
            }
        }
        reader.endObject()
        // calculate sigma for current epoch
        val totalDelegatedStake = stakeKeyToValue.values.sumByLong { it }
        poolIds.forEach { poolId ->
            val delegatedStake = stakeKeyToPoolId.filter {
                it.value == poolId
            }.keys.sumByLong { stakeKey ->
                stakeKeyToValue[stakeKey] ?: 0L
            }
            poolIdToSigma[poolId] = BigDecimal(delegatedStake).divide(BigDecimal(totalDelegatedStake), 34, RoundingMode.HALF_UP)
        }
    }

    override fun toJson(writer: JsonWriter, value: LeaderLogLedger?) {
        throw NotImplementedError("Not allowed to convert LeaderLogLedger to json!")
    }
}