package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RegCert(
    @param:Json(name = "type")
    val type: String,
    @param:Json(name = "description")
    val description: String,
    @param:Json(name = "cborHex")
    val cborHex: String
)
// json:
// {
//    "type": "CertificateShelley",
//    "description": "Stake Address Registration Certificate",
//    "cborHex": "82008200581c88e6792e133bde0c36c2fc4020bd2198d1bf43fdbedee46ef4b6d28f"
// }
// cbor:
// [
//    0,
//    [
//        0,
//        h'88e6792e133bde0c36c2fc4020bd2198d1bf43fdbedee46ef4b6d28f',
//    ],
// ]
//
// json:
// {
//    "type": "CertificateConway",
//    "description": "Stake Address Registration Certificate",
//    "cborHex": "83078200581c69a3e0b03feb30bd7b6d7669d9939a0fbc8d0f884260082befc578c71a001e8480"
// }
// cbor:
// [
//    7,
//    [
//        0,
//        h'69a3e0b03feb30bd7b6d7669d9939a0fbc8d0f884260082befc578c7',
//    ],
//    2000000_2,
// ]
