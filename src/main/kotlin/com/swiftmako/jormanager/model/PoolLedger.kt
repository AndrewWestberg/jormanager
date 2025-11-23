package com.swiftmako.jormanager.model

data class PoolLedger(
    val poolIdToLedgerParams: Map<String, PoolLedgerParams>,
    val poolIdToFutureLedgerParams: Map<String, PoolLedgerParams>,
)
