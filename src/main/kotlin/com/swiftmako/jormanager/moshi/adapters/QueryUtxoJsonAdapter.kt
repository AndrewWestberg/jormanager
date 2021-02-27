package com.swiftmako.jormanager.moshi.adapters

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.Utxo
import java.math.BigDecimal
import java.math.BigInteger

class QueryUtxoJsonAdapter : JsonAdapter<List<Utxo>>() {

    private val options = JsonReader.Options.of("amount")

    override fun fromJson(reader: JsonReader): List<Utxo>? {
        val utxos = mutableListOf<Utxo>()

        reader.beginObject()
        while (reader.hasNext()) {
            val hashAndTxIx = reader.nextName()
            val hash = hashAndTxIx.substringBefore('#')
            val ix = hashAndTxIx.substringAfter('#').toLong()
            val nativeAssets = mutableListOf<NativeAsset>()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.selectName(options)) {
                    0 -> {
                        // amount
                        val lovelace = if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                            // Mary era
                            var ll = BigInteger.ZERO
                            reader.beginArray()
                            while (reader.hasNext()) {
                                when (reader.peek()) {
                                    JsonReader.Token.NUMBER -> {
                                        // lovelaces value
                                        ll = BigDecimal(reader.nextString()).toBigInteger()
                                    }
                                    JsonReader.Token.BEGIN_ARRAY -> {
                                        // native asset array
                                        reader.beginArray()
                                        while (reader.hasNext()) {
                                            reader.beginArray()
                                            var policy = ""
                                            var name: String? = null
                                            var amount = BigInteger.ZERO
                                            while (reader.hasNext()) {
                                                when (reader.peek()) {
                                                    JsonReader.Token.STRING -> {
                                                        policy = reader.nextString()
                                                    }
                                                    JsonReader.Token.BEGIN_ARRAY -> {
                                                        reader.beginArray()
                                                        while (reader.hasNext()) {
                                                            reader.beginArray()
                                                            while (reader.hasNext()) {
                                                                when (reader.peek()) {
                                                                    JsonReader.Token.STRING -> {
                                                                        name = reader.nextString()
                                                                    }
                                                                    JsonReader.Token.NUMBER -> {
                                                                        amount = BigDecimal(reader.nextString()).toBigInteger()
                                                                    }
                                                                    else -> {
                                                                        reader.skipValue()
                                                                    }
                                                                }
                                                            }
                                                            reader.endArray()
                                                            if (name != null && policy.isNotBlank() && amount > BigInteger.ZERO) {
                                                                nativeAssets.add(NativeAsset(name, policy, amount))
                                                                name = null
                                                                amount = BigInteger.ZERO
                                                            }
                                                        }
                                                        reader.endArray()
                                                    }
                                                    else -> {
                                                        reader.skipValue()
                                                    }
                                                }
                                            }
                                            reader.endArray()
                                        }
                                        reader.endArray()
                                    }
                                    else -> {
                                        reader.skipValue()
                                    }
                                }
                            }
                            reader.endArray()
                            ll
                        } else {
                            // Before Mary era
                            BigDecimal(reader.nextString()).toBigInteger()
                        }
                        utxos.add(Utxo(hash, ix, lovelace, nativeAssets))
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
// Shelley era
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

// Mary era
// {
//    "9f8ea5fdaf7d0387dac3300d14396b7d42c41b850466aedcd106bae4cfff22d2#0": {
//        "amount": [
//            109999816899,
//            [
//                [
//                    "a52d2133008537f40f755932383466434543e87dbf8b99143fa7b5d9",
//                    [
//                        [
//                            "gold",
//                            1000
//                        ]
//                    ]
//                ]
//            ]
//        ],
//        "address": "607e8c76538b4aa50a62e6fe015ddb58a97b2131c6bb15fc0b8eeffd3a"
//    },
//    "d13647a327882b9571d78da42c30c127ee8080cd6a3fc685ee2abd047ee399f5#0": {
//        "amount": [
//            2000000,
//            [
//                [
//                    "34250edd1e9836f5378702fbf9416b709bc140e04f668cc355208518",
//                    [
//                        [
//                            "ATADAcoin",
//                            10
//                        ]
//                    ]
//                ]
//            ]
//        ],
//        "address": "607e8c76538b4aa50a62e6fe015ddb58a97b2131c6bb15fc0b8eeffd3a"
//    },
//    "27d92545a765d559dc2bf155c623d3bc29f6938f71a0fa1d88e6e573d2811e00#0": {
//        "amount": [
//            2000000,
//            [
//                [
//                    "34250edd1e9836f5378702fbf9416b709bc140e04f668cc355208518",
//                    [
//                        [
//                            "adosia",
//                            12345
//                        ]
//                    ]
//                ]
//            ]
//        ],
//        "address": "607e8c76538b4aa50a62e6fe015ddb58a97b2131c6bb15fc0b8eeffd3a"
//    }
//}