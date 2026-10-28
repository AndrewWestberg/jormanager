package com.swiftmako.jormanager.model

/** Slot is an unsigned ledger quantity at the JSON boundary, never a JavaScript Number. */
data class ChainPoint(val slot: String, val hash: String) {
    init {
        require(slot.matches(Regex("0|[1-9][0-9]*"))) { "Invalid chain slot" }
        require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid chain hash" }
    }
}

data class ChainReadState(
    val networkKey: String,
    val status: String,
    val reason: String?,
    val observedTip: ChainPoint?,
    val lastCompletePoint: ChainPoint?,
)
