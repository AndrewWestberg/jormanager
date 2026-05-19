package com.swiftmako.jormanager.tracing

data class TracingChainMetrics(
    val blockNum: Long,
    val slotNum: Long,
    val slotInEpoch: Long,
    val epoch: Long,
    val density: Double?,
    val tipBlock: String?,
)

data class TracingForgeMetrics(
    val forgingEnabled: Long?,
    val aboutToLead: Long?,
    val nodeNotLeader: Long?,
    val nodeIsLeader: Long?,
    val forgedSlotLast: Long?,
    val forged: Long?,
    val adopted: Long?,
)

data class TracingKesMetrics(
    val operationalCertificateStartKesPeriod: Long,
    val operationalCertificateExpiryKesPeriod: Long,
    val currentKesPeriod: Long,
    val remainingKesPeriods: Long,
)

data class TracingMempoolMetrics(
    val txsInMempool: Long? = null,
    val mempoolBytes: Long? = null,
    val txsProcessedNum: Long? = null,
    val txsSyncDuration: Long? = null,
    val txsSyncDurationTotal: Long? = null,
    val txsMempoolTimeoutSoft: Long? = null,
)

data class TracingMetricSnapshot(
    val chain: TracingChainMetrics?,
    val forge: TracingForgeMetrics,
    val kes: TracingKesMetrics?,
    val mempool: TracingMempoolMetrics,
    val peers: TracingPeerMetrics,
) {
    fun toNodeStateMetrics(
        peers: Int,
        incomingPeers: Int,
    ): NodeStateMetrics? {
        val chain = chain ?: return null
        val resolvedPeers = peers.takeIf { it > 0 } ?: this.peers.outgoingConnections?.toInt() ?: 0
        val resolvedIncomingPeers = incomingPeers.takeIf { it > 0 } ?: this.peers.incomingConnections?.toInt() ?: 0
        return NodeStateMetrics(
            peers = resolvedPeers,
            incomingPeers = resolvedIncomingPeers,
            blockHeight = chain.blockNum,
            remainingKESPeriods = kes?.remainingKesPeriods?.toNodeStatsIntOrNull() ?: 0,
            epoch = chain.epoch,
            slot = chain.slotNum,
            slotInEpoch = chain.slotInEpoch,
            txsProcessed = mempool.txsProcessedNum ?: 0,
        )
    }
}

data class TracingPeerMetrics(
    val outgoingConnections: Long? = null,
    val incomingConnections: Long? = null,
)

class TracingMetricDecoder {
    fun decode(metrics: Map<String, TracingRawMetricValue>): TracingMetricSnapshot =
        TracingMetricSnapshot(
            chain = metrics.toChainMetrics(),
            forge = metrics.toForgeMetrics(),
            kes = metrics.toKesMetrics(),
            mempool = metrics.toMempoolMetrics(),
            peers = metrics.toPeerMetrics(),
        )

    private fun Map<String, TracingRawMetricValue>.toKesMetrics(): TracingKesMetrics? {
        val start = intGauge(OPERATIONAL_CERTIFICATE_START_KES_PERIOD) ?: return null
        val expiry = intGauge(OPERATIONAL_CERTIFICATE_EXPIRY_KES_PERIOD) ?: return null
        val current = intGauge(CURRENT_KES_PERIOD) ?: return null
        val remaining = intGauge(REMAINING_KES_PERIODS) ?: return null
        return TracingKesMetrics(
            operationalCertificateStartKesPeriod = start,
            operationalCertificateExpiryKesPeriod = expiry,
            currentKesPeriod = current,
            remainingKesPeriods = remaining,
        )
    }

    private fun Map<String, TracingRawMetricValue>.toMempoolMetrics(): TracingMempoolMetrics =
        TracingMempoolMetrics(
            txsInMempool = intGauge(TXS_IN_MEMPOOL),
            mempoolBytes = intGauge(MEMPOOL_BYTES),
            txsProcessedNum = counter(TXS_PROCESSED_NUM),
            txsSyncDuration = intGauge(TXS_SYNC_DURATION),
            txsSyncDurationTotal = counter(TXS_SYNC_DURATION_TOTAL),
            txsMempoolTimeoutSoft = counter(TXS_MEMPOOL_TIMEOUT_SOFT),
        )

    private fun Map<String, TracingRawMetricValue>.toChainMetrics(): TracingChainMetrics? {
        val blockNum = intGauge(BLOCK_NUM) ?: return null
        val slotNum = intGauge(SLOT_NUM) ?: return null
        val slotInEpoch = intGauge(SLOT_IN_EPOCH) ?: return null
        val epoch = intGauge(EPOCH) ?: return null
        return TracingChainMetrics(
            blockNum = blockNum,
            slotNum = slotNum,
            slotInEpoch = slotInEpoch,
            epoch = epoch,
            density = realLabel(DENSITY),
            tipBlock = label(TIP_BLOCK),
        )
    }

    private fun Map<String, TracingRawMetricValue>.toForgeMetrics(): TracingForgeMetrics =
        TracingForgeMetrics(
            forgingEnabled = intGauge(FORGING_ENABLED),
            aboutToLead = counter(FORGE_ABOUT_TO_LEAD),
            nodeNotLeader = counter(FORGE_NODE_NOT_LEADER),
            nodeIsLeader = counter(FORGE_NODE_IS_LEADER),
            forgedSlotLast = intGauge(FORGED_SLOT_LAST),
            forged = counter(FORGE_FORGED),
            adopted = counter(FORGE_ADOPTED),
        )

    private fun Map<String, TracingRawMetricValue>.toPeerMetrics(): TracingPeerMetrics =
        TracingPeerMetrics(
            outgoingConnections = intGauge(OUTBOUND_CONNS),
            incomingConnections = intGauge(INBOUND_CONNS),
        )

    private fun Map<String, TracingRawMetricValue>.counter(name: String): Long? =
        (this[name] as? TracingRawMetricValue.Counter)?.value

    private fun Map<String, TracingRawMetricValue>.intGauge(name: String): Long? =
        (this[name] as? TracingRawMetricValue.IntGauge)?.value

    private fun Map<String, TracingRawMetricValue>.label(name: String): String? =
        (this[name] as? TracingRawMetricValue.Label)?.value

    private fun Map<String, TracingRawMetricValue>.realLabel(name: String): Double? =
        label(name)?.toDoubleOrNull()

    companion object {
        val DASHBOARD_REQUEST_NAMES =
            listOf(
                BLOCK_NUM,
                SLOT_NUM,
                SLOT_IN_EPOCH,
                EPOCH,
                DENSITY,
                TIP_BLOCK,
                FORGING_ENABLED,
                FORGE_ABOUT_TO_LEAD,
                FORGE_NODE_NOT_LEADER,
                FORGE_NODE_IS_LEADER,
                FORGED_SLOT_LAST,
                FORGE_FORGED,
                FORGE_ADOPTED,
                OPERATIONAL_CERTIFICATE_START_KES_PERIOD,
                OPERATIONAL_CERTIFICATE_EXPIRY_KES_PERIOD,
                CURRENT_KES_PERIOD,
                REMAINING_KES_PERIODS,
                TXS_IN_MEMPOOL,
                MEMPOOL_BYTES,
                TXS_PROCESSED_NUM,
                TXS_SYNC_DURATION,
                TXS_SYNC_DURATION_TOTAL,
                TXS_MEMPOOL_TIMEOUT_SOFT,
                OUTBOUND_CONNS,
                INBOUND_CONNS,
            )

        const val BLOCK_NUM = "cardano.node.metrics.blockNum_int"
        const val SLOT_NUM = "cardano.node.metrics.slotNum_int"
        const val SLOT_IN_EPOCH = "cardano.node.metrics.slotInEpoch_int"
        const val EPOCH = "cardano.node.metrics.epoch_int"
        const val DENSITY = "cardano.node.metrics.density_real"
        const val TIP_BLOCK = "cardano.node.metrics.tipBlock"

        const val FORGING_ENABLED = "cardano.node.metrics.forging_enabled_int"
        const val FORGE_ABOUT_TO_LEAD = "cardano.node.metrics.Forge.about-to-lead_counter"
        const val FORGE_NODE_NOT_LEADER = "cardano.node.metrics.Forge.node-not-leader_counter"
        const val FORGE_NODE_IS_LEADER = "cardano.node.metrics.Forge.node-is-leader_counter"
        const val FORGED_SLOT_LAST = "cardano.node.metrics.forgedSlotLast_int"
        const val FORGE_FORGED = "cardano.node.metrics.Forge.forged_counter"
        const val FORGE_ADOPTED = "cardano.node.metrics.Forge.adopted_counter"

        const val OPERATIONAL_CERTIFICATE_START_KES_PERIOD = "cardano.node.metrics.operationalCertificateStartKESPeriod_int"
        const val OPERATIONAL_CERTIFICATE_EXPIRY_KES_PERIOD = "cardano.node.metrics.operationalCertificateExpiryKESPeriod_int"
        const val CURRENT_KES_PERIOD = "cardano.node.metrics.currentKESPeriod_int"
        const val REMAINING_KES_PERIODS = "cardano.node.metrics.remainingKESPeriods_int"

        const val TXS_IN_MEMPOOL = "cardano.node.metrics.txsInMempool_int"
        const val MEMPOOL_BYTES = "cardano.node.metrics.mempoolBytes_int"
        const val TXS_PROCESSED_NUM = "cardano.node.metrics.txsProcessedNum_counter"
        const val TXS_SYNC_DURATION = "cardano.node.metrics.txsSyncDuration_int"
        const val TXS_SYNC_DURATION_TOTAL = "cardano.node.metrics.txsSyncDurationTotal_counter"
        const val TXS_MEMPOOL_TIMEOUT_SOFT = "cardano.node.metrics.txsMempoolTimeoutSoft_counter"
        const val OUTBOUND_CONNS = "cardano.node.metrics.connectionManager.outboundConns_int"
        const val INBOUND_CONNS = "cardano.node.metrics.connectionManager.inboundConns_int"
    }
}

private fun Long.toNodeStatsIntOrNull(): Int? =
    takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
