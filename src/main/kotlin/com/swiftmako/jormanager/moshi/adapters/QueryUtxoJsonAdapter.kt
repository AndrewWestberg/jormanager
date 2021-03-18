package com.swiftmako.jormanager.moshi.adapters

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.Utxo
import java.math.BigDecimal
import java.math.BigInteger

class QueryUtxoJsonAdapter : JsonAdapter<List<Utxo>>() {

    private val options = JsonReader.Options.of("value")
    private val policyNameOptions = JsonReader.Options.of("lovelace")

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
                        // value
                        var lovelace = BigInteger.ZERO
                        reader.beginObject()
                        while(reader.hasNext()) {
                            when (reader.selectName(policyNameOptions)) {
                                0 -> {
                                    // lovelace
                                    lovelace = BigDecimal(reader.nextString()).toBigInteger()
                                }
                                -1 -> {
                                    // token asset
                                    val policy = reader.nextName()
                                    reader.beginObject()
                                    while(reader.hasNext()) {
                                        val name = reader.nextName()
                                        val amount = BigDecimal(reader.nextString()).toBigInteger()
                                        if (name != null && policy.isNotBlank() && amount > BigInteger.ZERO) {
                                            nativeAssets.add(NativeAsset(name, policy, amount))
                                        }
                                    }
                                    reader.endObject()
                                }
                            }
                        }
                        reader.endObject()
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

// 1.26.0 era
//{
//    "55b0751876f5a846faa11b7aeaff979c00c42d21f1960d5610bbcf9a4fbd72ac#2": {
//        "address": "60da0eb5ed7611482ec5089b69d870e0c56c1c45180256112398e0835b",
//        "value": {
//            "34250edd1e9836f5378702fbf9416b709bc140e04f668cc355208518": {
//                "ADA": 1000,
//                "": 1
//            },
//            "b45fde8ab44e77aaa825e838715c5f2a1b60013c19efa639fa96da96": {
//                "BlueCheese": 3998
//            },
//            "ecd07b4ef62f37a68d145de8efd60c53d288dd5ffc641215120cc3db": {
//                "": 3
//            },
//            "lovelace": 16405517685
//        }
//    },
//    "bb82e22a3b1b78e24183d5ef7a83a5cbeb089d0ae9b0ca7f143703c3d9b11b69#0": {
//        "address": "60da0eb5ed7611482ec5089b69d870e0c56c1c45180256112398e0835b",
//        "value": {
//            "b45fde8ab44e77aaa825e838715c5f2a1b60013c19efa639fa96da96": {
//                "BlueCheese": 1
//            },
//            "lovelace": 4820683
//        }
//    },
//    "c246847b906d7ea96bc072f4e5b5b660aa514f26a2b84975fe0275da10c56f78#0": {
//        "address": "60da0eb5ed7611482ec5089b69d870e0c56c1c45180256112398e0835b",
//        "value": {
//            "b45fde8ab44e77aaa825e838715c5f2a1b60013c19efa639fa96da96": {
//                "BlueCheese": 1
//            },
//            "lovelace": 4820683
//        }
//    }
//}