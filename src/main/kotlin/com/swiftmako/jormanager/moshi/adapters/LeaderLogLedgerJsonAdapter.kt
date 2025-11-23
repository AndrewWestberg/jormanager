package com.swiftmako.jormanager.moshi.adapters

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.internal.Util
import com.swiftmako.jormanager.ktx.sumByBigInteger
import com.swiftmako.jormanager.model.LeaderLogLedger
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

class LeaderLogLedgerJsonAdapter(
    moshi: Moshi,
    private val poolIds: Set<String>
) : JsonAdapter<LeaderLogLedger>() {
    private val doubleAdapter: JsonAdapter<Double> = moshi.adapter(Double::class.java, emptySet(), "decentralisationParam")

    private val options: List<JsonReader.Options> =
        listOf(
            JsonReader.Options.of("stateBefore", "nesEs", "esPp", "esSnapshots", "esLState"),
            JsonReader.Options.of("decentralisationParam", "extraEntropy"),
            JsonReader.Options.of("pstakeSet", "_pstakeSet", "pstakeMark", "_pstakeMark"), // pstakeSet is current epoch, pstakeMark is future epoch
            JsonReader.Options.of("stake", "_stake", "delegations", "_delegations"),
            JsonReader.Options.of("utxoState", "_utxoState"),
            JsonReader.Options.of("ppups", "_ppups"),
            JsonReader.Options.of("proposals"),
            JsonReader.Options.of("decentralisationParam", "extraEntropy", "_d", "_extraEntropy"),
            JsonReader.Options.of("contents", "tag")
        )

    override fun fromJson(reader: JsonReader): LeaderLogLedger? {
        var decentralizationParameter = -1.0
        var futureDecentralizationParameter = -1.0
        var isFutureEntropySet = false
        var extraPraosEntropy: String? = null
        var futureExtraPraosEntropy: String? = null
        val poolIdToSigma = mutableMapOf<String, BigDecimal>()
        val futurePoolIdToSigma = mutableMapOf<String, BigDecimal>()
        var dProposalVotes = 0
        var isLedgerV2 = false
        var isLedgerV3 = false

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.selectName(options[0])) {
                0 -> {
                    // stateBefore
                    reader.beginObject()
                    isLedgerV3 = true
                    continue
                }
                1 -> {
                    // nesEs
                    reader.beginObject()
                    isLedgerV2 = true
                    continue
                }
                2 -> {
                    // esPp
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.selectName(options[1])) {
                            0 -> {
                                // decentralisationParam
                                decentralizationParameter = doubleAdapter.fromJson(reader)
                                    ?: throw Util.unexpectedNull("decentralisationParam", "decentralisationParam", reader)
                            }
                            1 -> {
                                // extraEntropy
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.selectName(options[8])) {
                                        0 -> {
                                            // contents
                                            extraPraosEntropy = reader.nextString()
                                        }
                                        1 -> {
                                            // tag
                                            reader.nextString()
                                        }
                                        -1 -> {
                                            // tag or anything else
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
                3 -> {
                    // esSnapshots
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.selectName(options[2])) {
                            0, 1 -> {
                                // pstakeSet
                                calculateSigmaValues(reader, poolIdToSigma)
                            }
                            2, 3 -> {
                                // pstakeMark
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
                4 -> {
                    // esLState
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.selectName(options[4])) {
                            0, 1 -> {
                                // utxoState
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.selectName(options[5])) {
                                        0, 1 -> {
                                            // ppups
                                            reader.beginObject()
                                            while (reader.hasNext()) {
                                                when (reader.selectName(options[6])) {
                                                    0 -> {
                                                        // proposals
                                                        if (isLedgerV3) {
                                                            reader.beginArray()
                                                            while (reader.hasNext()) {
                                                                reader.beginArray()
                                                                while (reader.hasNext()) {
                                                                    reader.nextString() // consume the voter id name
                                                                    reader.beginObject()
                                                                    while (reader.hasNext()) {
                                                                        when (reader.selectName(options[7])) {
                                                                            0 -> {
                                                                                // decentralisationParam
                                                                                if (reader.peek() == JsonReader.Token.NULL) {
                                                                                    reader.skipValue()
                                                                                } else {
                                                                                    dProposalVotes++
                                                                                    if (dProposalVotes == 1) { // changed from 5 -> 1 because only 1 for guild network
                                                                                        futureDecentralizationParameter = reader.nextDouble()
                                                                                    } else {
                                                                                        reader.skipValue()
                                                                                    }
                                                                                }
                                                                            }
                                                                            1 -> {
                                                                                // extraEntropy
                                                                                reader.beginObject()
                                                                                while (reader.hasNext()) {
                                                                                    when (reader.selectName(options[8])) {
                                                                                        0 -> {
                                                                                            // contents
                                                                                            futureExtraPraosEntropy = reader.nextString()
                                                                                            isFutureEntropySet = true
                                                                                        }
                                                                                        1 -> {
                                                                                            // tag
                                                                                            reader.nextString()
                                                                                            isFutureEntropySet = true
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
                                                                reader.endArray()
                                                            }
                                                            reader.endArray()
                                                        } else {
                                                            // parse proposals the old way
                                                            // proposals
                                                            reader.beginObject()
                                                            while (reader.hasNext()) {
                                                                reader.nextName() // consume the voter id name
                                                                reader.beginObject()
                                                                while (reader.hasNext()) {
                                                                    when (reader.selectName(options[7])) {
                                                                        2 -> {
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
                                                                        3 -> {
                                                                            // _extraEntropy
                                                                            reader.beginObject()
                                                                            while (reader.hasNext()) {
                                                                                when (reader.selectName(options[8])) {
                                                                                    0 -> {
                                                                                        // contents
                                                                                        futureExtraPraosEntropy = reader.nextString()
                                                                                        isFutureEntropySet = true
                                                                                    }
                                                                                    1 -> {
                                                                                        // tag
                                                                                        reader.nextString()
                                                                                        isFutureEntropySet = true
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
                                                            reader.endObject()
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
        if (isLedgerV3 || isLedgerV2) {
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

        if (futureExtraPraosEntropy == null && !isFutureEntropySet) {
            futureExtraPraosEntropy = extraPraosEntropy
        }

        return LeaderLogLedger(
            decentralizationParameter = decentralizationParameter,
            futureDecentralizationParameter = futureDecentralizationParameter,
            poolIdToSigma = poolIdToSigma,
            futurePoolIdToSigma = futurePoolIdToSigma,
            extraPraosEntropy = extraPraosEntropy,
            futureExtraPraosEntropy = futureExtraPraosEntropy,
        )
    }

    private fun calculateSigmaValues(
        reader: JsonReader,
        poolIdToSigma: MutableMap<String, BigDecimal>
    ) {
        val stakeKeyToValue = mutableMapOf<String, BigInteger>()
        val stakeKeyToPoolId = mutableMapOf<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.selectName(options[3])) {
                0, 1 -> {
                    // stake, _stake
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginArray()
                        reader.beginObject()
                        reader.skipName() // skip the name which is "key hash"
                        val stakeKey = reader.nextString()
                        reader.endObject()
                        val stakeValue = BigDecimal(reader.nextString()).toBigInteger()
                        if (stakeValue > BigInteger.ZERO) {
                            stakeKeyToValue[stakeKey] = stakeValue
                        }
                        reader.endArray()
                    }
                    reader.endArray()
                }
                2, 3 -> {
                    // delegations, _delegations
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
        val totalDelegatedStake = stakeKeyToValue.values.sumByBigInteger { it }
        poolIds.forEach { poolId ->
            val delegatedStake =
                stakeKeyToPoolId
                    .filter {
                        it.value == poolId
                    }.keys
                    .sumByBigInteger { stakeKey ->
                        stakeKeyToValue[stakeKey] ?: BigInteger.ZERO
                    }
            poolIdToSigma[poolId] = BigDecimal(delegatedStake).divide(BigDecimal(totalDelegatedStake), 34, RoundingMode.HALF_UP)
        }
    }

    override fun toJson(
        writer: JsonWriter,
        value: LeaderLogLedger?
    ): Unit = throw NotImplementedError("Not allowed to convert LeaderLogLedger to json!")
}
