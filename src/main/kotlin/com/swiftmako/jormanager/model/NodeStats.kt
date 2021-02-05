package com.swiftmako.jormanager.model

data class NodeStats(
        val isDefault: Boolean,
        val timestamp: Long,
        val nodeName: String,
        val color: String,
        val peers: Int?,
        val incomingPeers: Int?,
        val blockHeight: Long?,
        val remainingKESPeriods: Int?,
        val epoch: Long?,
        val slot: Long?,
        val slotInEpoch: Long?,
        val txsProcessed: Long?,
        val epochLength: Long,
)