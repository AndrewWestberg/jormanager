package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.DecodedInput
import com.swiftmako.jormanager.model.DecodedOutput
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.tables.*
import com.swiftmako.jormanager.utils.Bech32
import com.swiftmako.jormanager.utils.CborSpan
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.math.BigInteger

/** Maintained projections only. The coordinator owns every adoption/rollback transaction. */
object LedgerRepository {
    private val nextAssetLinkId = org.jetbrains.exposed.v1.core.Sequence("hibernate_sequence").nextLongVal()

    class ProjectionContext(
        val network: String,
        val inclusion: Long,
        val height: BigDecimal,
        val slot: BigDecimal,
        val isNewInclusion: Boolean,
    ) {
        var nextSequence: Int = 0
        val addressIds = HashMap<String, Long>()
        val assetIds = HashMap<Pair<String, String>, Long>()
        internal class PendingInput(val member: Long, val input: DecodedInput, val role: String, val position: Int, val effective: Boolean, val resolved: Long?)
        internal class PendingData(val member: Long, val family: String, val position: Int, val raw: CborSpan, val typed: String, val effective: Boolean)
        internal val pendingInputs = ArrayList<PendingInput>()
        internal val pendingData = ArrayList<PendingData>()
    }

    fun queryUtxos(network: String, address: String): List<Utxo> = transaction {
        (LedgerUtxosTable innerJoin LedgerTable).selectAll().where {
            (LedgerUtxosTable.networkKey eq network) and (LedgerTable.address eq address) and
                LedgerUtxosTable.spendingInclusionId.isNull()
        }.map { row ->
            val assets = (LedgerUtxoAssetsTable innerJoin LedgerAssetsTable).selectAll().where {
                LedgerUtxoAssetsTable.ledgerUtxoId eq row[LedgerUtxosTable.id].value
            }.map { asset -> NativeAsset(asset[LedgerAssetsTable.name], asset[LedgerAssetsTable.policy], BigInteger(asset[LedgerUtxoAssetsTable.amount])) }
            Utxo(row[LedgerUtxosTable.txId], row[LedgerUtxosTable.txIx].toLong(), BigInteger(row[LedgerUtxosTable.lovelace]), assets)
        }
    }

    fun seedGenesis(network: String, outputs: List<com.swiftmako.jormanager.nodeclient.protocols.blockfetch.DecodedGenesisOutput>) {
        outputs.forEach { output ->
            val ledgerId = addressId(output.address)
            LedgerUtxosTable.insert {
                it[LedgerUtxosTable.ledgerId] = ledgerId; it[networkKey] = network
                it[txId] = output.txId.toHexString(); it[txIx] = 0; it[lovelace] = output.coin.toString()
                it[blockCreated] = java.math.BigDecimal.ZERO; it[slotCreated] = java.math.BigDecimal.ZERO
                it[blockSpent] = null; it[slotSpent] = null; it[spendingInclusionId] = null; it[outputOccurrenceId] = null
            }
        }
    }

    fun currentOutput(network: String, txId: ByteArray, index: Int): ResultRow? =
        LedgerUtxosTable.select(LedgerUtxosTable.id, LedgerUtxosTable.outputOccurrenceId, LedgerUtxosTable.lovelace).where {
            (LedgerUtxosTable.networkKey eq network) and (LedgerUtxosTable.txId eq txId.toHexString()) and
                (LedgerUtxosTable.txIx eq index) and LedgerUtxosTable.spendingInclusionId.isNull()
        }.singleOrNull()

    fun createOutput(context: ProjectionContext, member: Long, txId: ByteArray, index: Int, output: DecodedOutput, collateral: Boolean, effective: Boolean): Long {
        val existing = if (context.isNewInclusion) null else OutputOccurrencesTable.select(OutputOccurrencesTable.id).where {
            (OutputOccurrencesTable.member eq member) and (OutputOccurrencesTable.index eq index)
        }.singleOrNull()
        val outputId = existing?.get(OutputOccurrencesTable.id)?.value ?: OutputOccurrencesTable.insertReturning(returning = listOf(OutputOccurrencesTable.id)) {
            it[OutputOccurrencesTable.member] = member
            it[OutputOccurrencesTable.index] = index
            it[raw] = output.raw.bytes()
            it[address] = output.address
            it[coin] = output.coin.toBigDecimal()
            it[datum] = output.datum?.bytes()
            it[referenceScript] = output.referenceScript?.bytes()
            it[collateralReturn] = collateral
            it[OutputOccurrencesTable.effective] = effective
        }.single()[OutputOccurrencesTable.id].value
        if (existing == null) output.assets.chunked(256).forEach { assets ->
            OutputAssetsTable.batchInsert(assets, useMultiRowValues = true, shouldReturnGeneratedValues = false) { asset ->
                this[OutputAssetsTable.output] = outputId
                this[OutputAssetsTable.policy] = asset.policy
                this[OutputAssetsTable.name] = asset.name
                this[OutputAssetsTable.quantity] = asset.quantity.toBigDecimal()
            }
        }
        if (!effective) return outputId
        check(currentOutput(context.network, txId, index) == null) { "Duplicate adopted output reference" }
        val address = addressText(output.address)
        val ledgerId = context.addressIds.getOrPut(address) { addressId(output.address, address) }
        val projectionId = LedgerUtxosTable.insertReturning(returning = listOf(LedgerUtxosTable.id)) {
            it[LedgerUtxosTable.ledgerId] = ledgerId
            it[networkKey] = context.network
            it[outputOccurrenceId] = outputId
            it[LedgerUtxosTable.txId] = txId.toHexString()
            it[txIx] = index
            it[lovelace] = output.coin.toString()
            it[blockCreated] = context.height
            it[slotCreated] = context.slot
            it[blockSpent] = null; it[slotSpent] = null; it[spendingInclusionId] = null
        }.single()[LedgerUtxosTable.id].value
        val assets = output.assets.map { asset ->
            val policy = asset.policy.toHexString(); val name = asset.name.toHexString()
            val assetId = context.assetIds.getOrPut(policy to name) {
                LedgerAssetsTable.select(LedgerAssetsTable.id).where { (LedgerAssetsTable.policy eq policy) and (LedgerAssetsTable.name eq name) }
                    .firstOrNull()?.get(LedgerAssetsTable.id)?.value ?: run {
                    LedgerAssetsTable.insertIgnore {
                        it[LedgerAssetsTable.policy] = policy; it[LedgerAssetsTable.name] = name; it[image] = ""; it[description] = null
                    }
                    LedgerAssetsTable.select(LedgerAssetsTable.id).where { (LedgerAssetsTable.policy eq policy) and (LedgerAssetsTable.name eq name) }.single()[LedgerAssetsTable.id].value
                }
            }
            assetId to asset.quantity.toString()
        }
        assets.chunked(256).forEach { chunk ->
            LedgerUtxoAssetsTable.batchInsert(chunk, useMultiRowValues = true, shouldReturnGeneratedValues = false) { (assetId, amount) ->
                this[LedgerUtxoAssetsTable.id] = nextAssetLinkId
                this[LedgerUtxoAssetsTable.ledgerUtxoId] = projectionId; this[LedgerUtxoAssetsTable.ledgerAssetId] = assetId; this[LedgerUtxoAssetsTable.amount] = amount
            }
        }
        return outputId
    }

    private fun addressId(bytes: ByteArray, address: String = addressText(bytes)): Long {
        LedgerTable.select(LedgerTable.id).where { LedgerTable.address eq address }.singleOrNull()?.let { return it[LedgerTable.id].value }
        LedgerTable.insertIgnore {
            it[LedgerTable.address] = address
            it[stakeAddress] = if (bytes.size == 57 && (bytes[0].toInt() and 0xf0) < 0x40) {
                val stake = byteArrayOf(((bytes[0].toInt() and 15) or if ((bytes[0].toInt() and 0x20) == 0) 0xe0 else 0xf0).toByte()) + bytes.copyOfRange(29, 57)
                Bech32.encode(if ((stake[0].toInt() and 15) == 1) "stake" else "stake_test", stake)
            } else null
        }
        return LedgerTable.select(LedgerTable.id).where { LedgerTable.address eq address }.single()[LedgerTable.id].value
    }

    fun spend(context: ProjectionContext, txId: ByteArray, index: Int): Long? {
        val current = LedgerUtxosTable.updateReturning(
            returning = listOf(LedgerUtxosTable.outputOccurrenceId),
            where = {
                (LedgerUtxosTable.networkKey eq context.network) and (LedgerUtxosTable.txId eq txId.toHexString()) and
                    (LedgerUtxosTable.txIx eq index) and LedgerUtxosTable.spendingInclusionId.isNull()
            },
        ) {
            it[spendingInclusionId] = context.inclusion
            it[blockSpent] = context.height
            it[slotSpent] = context.slot
        }.singleOrNull() ?: throw IllegalStateException("Referenced input is outside complete indexed coverage")
        return current[LedgerUtxosTable.outputOccurrenceId]
    }

    fun rollbackProjection(network: String, inclusion: Long) {
        LedgerUtxosTable.update({ (LedgerUtxosTable.networkKey eq network) and (LedgerUtxosTable.spendingInclusionId eq inclusion) }) {
            it[spendingInclusionId] = null; it[blockSpent] = null; it[slotSpent] = null
        }
        val outputs = (OutputOccurrencesTable innerJoin TransactionMembersTable innerJoin TransactionBatchesTable)
            .select(OutputOccurrencesTable.id).where { TransactionBatchesTable.inclusion eq inclusion }
            .map { it[OutputOccurrencesTable.id].value }
        if (outputs.isNotEmpty()) LedgerUtxosTable.deleteWhere { (networkKey eq network) and (outputOccurrenceId inList outputs) }
    }

    private fun addressText(address: ByteArray): String {
        require(address.isNotEmpty()) { "Empty output address" }
        return if ((address[0].toInt() and 0xf0) == 0x80) {
            // Byron base58 is presentation only; binary identity remains in the occurrence.
            base58(address)
        } else Bech32.encode(if ((address[0].toInt() and 15) == 1) "addr" else "addr_test", address)
    }

    private fun base58(bytes: ByteArray): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        val scratch = bytes.copyOf()
        val encoded = CharArray(bytes.size * 2)
        var position = encoded.size
        var zeros = 0
        while (zeros < bytes.size && bytes[zeros] == 0.toByte()) zeros++
        var start = zeros
        while (start < scratch.size) {
            var remainder = 0
            for (i in start until scratch.size) {
                val value = remainder * 256 + (scratch[i].toInt() and 255)
                scratch[i] = (value / 58).toByte()
                remainder = value % 58
            }
            encoded[--position] = alphabet[remainder]
            while (start < scratch.size && scratch[start] == 0.toByte()) start++
        }
        repeat(zeros) { encoded[--position] = '1' }
        return String(encoded, position, encoded.size - position)
    }
}
