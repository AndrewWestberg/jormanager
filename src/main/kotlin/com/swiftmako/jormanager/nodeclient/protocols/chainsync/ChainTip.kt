package com.swiftmako.jormanager.nodeclient.protocols.chainsync

data class ChainTip(
    val slot: Long,
    val block: Long,
    val hash: String,
)
