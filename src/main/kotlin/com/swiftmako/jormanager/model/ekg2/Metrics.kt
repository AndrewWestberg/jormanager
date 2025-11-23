package com.swiftmako.jormanager.model.ekg2

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Metrics(
    @param:Json(name = "blockNum")
    val blockNum: BlockNum = BlockNum(),
    @param:Json(name = "connectionManager")
    val connectionManager: ConnectionManager = ConnectionManager(),
    @param:Json(name = "currentKESPeriod")
    val currentKESPeriod: CurrentKESPeriod = CurrentKESPeriod(),
    @param:Json(name = "delegMapSize")
    val delegMapSize: DelegMapSize = DelegMapSize(),
    @param:Json(name = "density")
    val density: Density = Density(),
    @param:Json(name = "epoch")
    val epoch: Epoch = Epoch(),
    @param:Json(name = "Forge")
    val forge: Forge = Forge(),
    @param:Json(name = "Mem")
    val mem: Mem = Mem(),
    @param:Json(name = "nodeStartTime")
    val nodeStartTime: NodeStartTime = NodeStartTime(),
    @param:Json(name = "operationalCertificateExpiryKESPeriod")
    val operationalCertificateExpiryKESPeriod: OperationalCertificateExpiryKESPeriod = OperationalCertificateExpiryKESPeriod(),
    @param:Json(name = "operationalCertificateStartKESPeriod")
    val operationalCertificateStartKESPeriod: OperationalCertificateStartKESPeriod = OperationalCertificateStartKESPeriod(),
    @param:Json(name = "remainingKESPeriods")
    val remainingKESPeriods: RemainingKESPeriods = RemainingKESPeriods(),
    @param:Json(name = "slotInEpoch")
    val slotInEpoch: SlotInEpoch = SlotInEpoch(),
    @param:Json(name = "slotNum")
    val slotNum: SlotNum = SlotNum(),
    @param:Json(name = "Stat")
    val stat: Stat = Stat(),
    @param:Json(name = "utxoSize")
    val utxoSize: UtxoSize = UtxoSize(),
    @param:Json(name = "txsProcessedNum")
    val txsProcessedNum: TxsProcessedNum = TxsProcessedNum()
)
