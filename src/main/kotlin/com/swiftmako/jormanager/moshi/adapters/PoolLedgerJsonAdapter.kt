package com.swiftmako.jormanager.moshi.adapters

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.model.PoolLedger
import com.swiftmako.jormanager.model.PoolLedgerParams

class PoolLedgerJsonAdapter(moshi: Moshi, poolIds: Set<String>) : JsonAdapter<PoolLedger>() {

    private val poolLedgerParamsAdapter = moshi.adapter(PoolLedgerParams::class.java)

    private val options: List<JsonReader.Options> = listOf(
            JsonReader.Options.of("nesEs"),
            JsonReader.Options.of("esLState"),
            JsonReader.Options.of("_delegationState"),
            JsonReader.Options.of("_pstate"),
            JsonReader.Options.of("_pParams", "_fPParams"),
            JsonReader.Options.of(*poolIds.toTypedArray())
    )

    override fun fromJson(reader: JsonReader): PoolLedger? {
        val poolIdToLedgerParams = mutableMapOf<String, PoolLedgerParams>()
        val poolIdToFutureLedgerParams = mutableMapOf<String, PoolLedgerParams>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.selectName(options[0])) {
                0 -> {
                    // nesEs
                    reader.beginObject()
                    while(reader.hasNext()) {
                        when (reader.selectName(options[1])) {
                            0 -> {
                                // esLState
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.selectName(options[2])) {
                                        0 -> {
                                            // _delegationState
                                            reader.beginObject()
                                            while (reader.hasNext()) {
                                                when (reader.selectName(options[3])) {
                                                    0 -> {
                                                        // _pstate
                                                        reader.beginObject()
                                                        while (reader.hasNext()) {
                                                            when (reader.selectName(options[4])) {
                                                                0 -> {
                                                                    // _pParams
                                                                    reader.beginObject()
                                                                    while (reader.hasNext()) {
                                                                        when (val poolIdIndex = reader.selectName(options[5])) {
                                                                            -1 -> {
                                                                                reader.skipName()
                                                                                reader.skipValue()
                                                                            }
                                                                            else -> {
                                                                                val poolId = options[5].strings()[poolIdIndex]
                                                                                val poolLedgerParams = poolLedgerParamsAdapter.fromJson(reader)!!
                                                                                poolIdToLedgerParams[poolId] = poolLedgerParams
                                                                            }
                                                                        }
                                                                    }
                                                                    reader.endObject()
                                                                }
                                                                1 -> {
                                                                    // _fPParams
                                                                    reader.beginObject()
                                                                    while (reader.hasNext()) {
                                                                        when (val poolIdIndex = reader.selectName(options[5])) {
                                                                            -1 -> {
                                                                                reader.skipName()
                                                                                reader.skipValue()
                                                                            }
                                                                            else -> {
                                                                                val poolId = options[5].strings()[poolIdIndex]
                                                                                val poolLedgerParams = poolLedgerParamsAdapter.fromJson(reader)!!
                                                                                poolIdToFutureLedgerParams[poolId] = poolLedgerParams
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

        return PoolLedger(
                poolIdToLedgerParams = poolIdToLedgerParams,
                poolIdToFutureLedgerParams = poolIdToFutureLedgerParams,
        )
    }


    override fun toJson(writer: JsonWriter, value: PoolLedger?) {
        throw NotImplementedError("Not allowed to convert PoolLedger to json!")
    }
}