package com.swiftmako.jormanager.moshi.adapters

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.swiftmako.jormanager.model.Utxo

class QueryUtxoJsonAdapter : JsonAdapter<List<Utxo>>() {

    private val options = JsonReader.Options.of("amount")

    override fun fromJson(reader: JsonReader): List<Utxo>? {
        val utxos = mutableListOf<Utxo>()

        reader.beginObject()
        while(reader.hasNext()) {
            val hashAndTxIx = reader.nextName()
            val hash = hashAndTxIx.substringBefore('#')
            val ix = hashAndTxIx.substringAfter('#').toLong()
            reader.beginObject()
            while(reader.hasNext()) {
                when(reader.selectName(options)) {
                    0 -> {
                        // amount
                        val lovelace = reader.nextLong()
                        utxos.add(Utxo(hash, ix, lovelace))
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

        return utxos
    }

    override fun toJson(writer: JsonWriter, value: List<Utxo>?) {
        throw NotImplementedError("Not allowed to convert QueryUtxo to json!")
    }
}

// {
//    "1a8f5696a4f374ce6942dd498d90827e92ac27fc5133ba14cfa4fa61f4092f0d#0": {
//        "amount": 1828603,
//        "address": "6129fd280480232d72b2839fcf47275e790a1a3a45dafafd8f64180612"
//    },
//    "fa3d777c71b07563e381ba7285a708f9a213330365fdee86db5653f5b73c1bf4#0": {
//        "amount": 1973709508,
//        "address": "6129fd280480232d72b2839fcf47275e790a1a3a45dafafd8f64180612"
//    }
//}