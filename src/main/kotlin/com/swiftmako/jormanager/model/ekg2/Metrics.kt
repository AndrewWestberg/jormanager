package com.swiftmako.jormanager.model.ekg2


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Metrics(
    @Json(name = "blockNum")
    val blockNum: BlockNum = BlockNum(),
    @Json(name = "connectionManager")
    val connectionManager: ConnectionManager = ConnectionManager(),
    @Json(name = "currentKESPeriod")
    val currentKESPeriod: CurrentKESPeriod = CurrentKESPeriod(),
    @Json(name = "delegMapSize")
    val delegMapSize: DelegMapSize = DelegMapSize(),
    @Json(name = "density")
    val density: Density = Density(),
    @Json(name = "epoch")
    val epoch: Epoch = Epoch(),
    @Json(name = "Forge")
    val forge: Forge = Forge(),
    @Json(name = "Mem")
    val mem: Mem = Mem(),
    @Json(name = "nodeStartTime")
    val nodeStartTime: NodeStartTime = NodeStartTime(),
    @Json(name = "operationalCertificateExpiryKESPeriod")
    val operationalCertificateExpiryKESPeriod: OperationalCertificateExpiryKESPeriod = OperationalCertificateExpiryKESPeriod(),
    @Json(name = "operationalCertificateStartKESPeriod")
    val operationalCertificateStartKESPeriod: OperationalCertificateStartKESPeriod = OperationalCertificateStartKESPeriod(),
    @Json(name = "remainingKESPeriods")
    val remainingKESPeriods: RemainingKESPeriods = RemainingKESPeriods(),
    @Json(name = "slotInEpoch")
    val slotInEpoch: SlotInEpoch = SlotInEpoch(),
    @Json(name = "slotNum")
    val slotNum: SlotNum = SlotNum(),
    @Json(name = "Stat")
    val stat: Stat = Stat(),
    @Json(name = "utxoSize")
    val utxoSize: UtxoSize = UtxoSize(),
    @Json(name = "txsProcessedNum")
    val txsProcessedNum: TxsProcessedNum = TxsProcessedNum()
)