package com.swiftmako.jormanager.model

data class Utxo(
        val hash: String,
        val ix: Long,
        val lovelace: Long
)