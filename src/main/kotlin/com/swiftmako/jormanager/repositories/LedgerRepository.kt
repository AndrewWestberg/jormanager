package com.swiftmako.jormanager.repositories

import com.github.benmanes.caffeine.cache.Caffeine
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.*
import com.swiftmako.jormanager.tables.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.time.Duration

object LedgerRepository {
    private val log by lazy { LoggerFactory.getLogger("LedgerRepository") }

    fun queryUtxos(address: String): List<Utxo> = transaction {
        LedgerUtxosTable.innerJoin(LedgerTable, { ledgerId }, { LedgerTable.id }, { LedgerTable.address eq address })
            .select {
                LedgerUtxosTable.blockSpent.isNull() and LedgerUtxosTable.slotSpent.isNull()
            }.map { row ->
                val ledgerUtxoId = row[LedgerUtxosTable.id].value

                val nativeAssets = LedgerUtxoAssetsTable.innerJoin(
                    LedgerAssetsTable,
                    { ledgerAssetId },
                    { LedgerAssetsTable.id },
                    { LedgerUtxoAssetsTable.ledgerUtxoId eq ledgerUtxoId })
                    .selectAll().map { naRow ->
                        NativeAsset(
                            name = naRow[LedgerAssetsTable.name],
                            policy = naRow[LedgerAssetsTable.policy],
                            amount = BigInteger(naRow[LedgerUtxoAssetsTable.amount])
                        )
                    }

                Utxo(
                    hash = row[LedgerUtxosTable.txId],
                    ix = row[LedgerUtxosTable.txIx].toLong(),
                    lovelace = BigInteger(row[LedgerUtxosTable.lovelace]),
                    nativeAssets = nativeAssets
                )
            }
    }

    fun doRollback(blockNumber: Long) {
        BlockFetchTable.deleteWhere { BlockFetchTable.blockNumber greaterEq blockNumber }
        LedgerUtxosTable.deleteWhere { LedgerUtxosTable.blockCreated greaterEq blockNumber }
        LedgerUtxosTable.update({ LedgerUtxosTable.blockSpent greaterEq blockNumber }) {
            it[blockSpent] = null
            it[slotSpent] = null
        }
    }

    fun upcertNativeAssets(nativeAssetsMetadata: Set<NativeAssetMetadata>) {
        nativeAssetsMetadata.forEach { nativeAssetMetadata ->
            LedgerAssetsTable.select {
                (LedgerAssetsTable.policy eq nativeAssetMetadata.assetPolicy) and
                        (LedgerAssetsTable.name eq nativeAssetMetadata.assetName)
            }.firstOrNull()?.let { existingRow ->
                // Do update
                LedgerAssetsTable.update({ LedgerAssetsTable.id eq existingRow[LedgerAssetsTable.id] }) { row ->
                    row[policy] = nativeAssetMetadata.assetPolicy
                    row[name] = nativeAssetMetadata.assetName
                    row[image] = nativeAssetMetadata.metadataImage
                    row[description] = nativeAssetMetadata.metadataDescription
                }
            } ?: run {
                // Do insert
                LedgerAssetsTable.insert { row ->
                    row[policy] = nativeAssetMetadata.assetPolicy
                    row[name] = nativeAssetMetadata.assetName
                    row[image] = nativeAssetMetadata.metadataImage
                    row[description] = nativeAssetMetadata.metadataDescription
                }
            }
        }
    }

    fun pruneSpent(beforeSlot: Long) {
        LedgerUtxosTable.deleteWhere { LedgerUtxosTable.slotSpent less beforeSlot }

        //TODO: this takes forever to complete
        //LedgerTable.deleteWhere { notExists(LedgerUtxosTable.slice(LedgerUtxosTable.id).select { LedgerUtxosTable.ledgerId eq LedgerTable.id })  }
    }

    fun spendUtxos(slotNumber: Long, blockNumber: Long, spentUtxos: Set<SpentUtxo>) {
        var count = 0
        spentUtxos.forEach { spentUtxo ->
            count += LedgerUtxosTable.update({
                (LedgerUtxosTable.txId eq spentUtxo.hash) and
                        (LedgerUtxosTable.txIx eq spentUtxo.ix.toInt())
            }) { row ->
                row[blockSpent] = blockNumber
                row[slotSpent] = slotNumber
            }
        }
//        if (count > 0 && count == spentUtxos.size) {
//            log.warn("spentUtxo update match: $count")
//        }
//        if (count != spentUtxos.size) {
//            log.warn("spentUtxo update mismatch: spentUtxos.size=${spentUtxos.size}, count=${count}")
//            log.warn(spentUtxos.toString())
//        }
    }

    private val ledgerTableIdCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(30_000L)
        .build<String, Long?> { address ->
            LedgerTable.slice(LedgerTable.id).select {
                LedgerTable.address eq address
            }.limit(1).firstOrNull()?.let { row ->
                row[LedgerTable.id].value
            }
        }
    private val ledgerAssetsTableIdCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(30_000L)
        .build<Pair<String, String>, Long?> { (policy, name) ->
            LedgerAssetsTable.select {
                (LedgerAssetsTable.policy eq policy) and (LedgerAssetsTable.name eq name)
            }.limit(1).firstOrNull()?.let { row ->
                row[LedgerAssetsTable.id].value
            }
        }

    private val ledgerAddressesToInsert = ArrayList<CreatedUtxo>(400)
    private val addressToLedgerIdMap = LinkedHashMap<String, Long>(400)
    private val nativeAssetsToInsert = LinkedHashSet<NativeAsset>(400)
    private val nativeAssetToLedgerAssetIdMap = LinkedHashMap<NativeAsset, Long>(400)
    fun createUtxos(slotNumber: Long, blockNumber: Long, createdUtxos: Set<CreatedUtxo>) {
        // information to temporarily cache
        ledgerAddressesToInsert.clear()
        addressToLedgerIdMap.clear()
        nativeAssetsToInsert.clear()
        nativeAssetToLedgerAssetIdMap.clear()

        // figure out which LedgerAddresses already exist and store their id values
//        val queryAddressTime = measureTimeMillis {
        createdUtxos.forEach { createdUtxo ->
            val ledgerTableId = ledgerTableIdCache[createdUtxo.address]
            ledgerTableId?.let {
                addressToLedgerIdMap[createdUtxo.address] = it
            } ?: ledgerAddressesToInsert.add(createdUtxo)
        }
//        }

        // batch insert into ledger table those that don't already exist
//        val insertAddressTime = measureTimeMillis {
        val distinctLedgerAddressesToInsert =
            ledgerAddressesToInsert.distinctBy { createdUtxo -> createdUtxo.address }
        val ledgerTableInsertedIds =
            LedgerTable.batchInsert(distinctLedgerAddressesToInsert) { utxo ->
                this[LedgerTable.address] = utxo.address
                this[LedgerTable.stakeAddress] = utxo.stakeAddress
            }.map { row -> row[LedgerTable.id].value }
        distinctLedgerAddressesToInsert.zip(ledgerTableInsertedIds)
            .forEach { (utxo, ledgerTableId) -> addressToLedgerIdMap[utxo.address] = ledgerTableId }
        ledgerTableIdCache.invalidateAll(distinctLedgerAddressesToInsert.map { it.address })
//        }

        // batch insert new utxos
//        val insertUtxoTime = measureTimeMillis {
        val ledgerUtxoTableInsertedIds = LedgerUtxosTable.batchInsert(createdUtxos) { createdUtxo ->
            this[LedgerUtxosTable.ledgerId] = addressToLedgerIdMap[createdUtxo.address]!!
            this[LedgerUtxosTable.txId] = createdUtxo.hash
            this[LedgerUtxosTable.txIx] = createdUtxo.ix.toInt()
            this[LedgerUtxosTable.lovelace] = createdUtxo.lovelace.toString()
            this[LedgerUtxosTable.blockCreated] = blockNumber
            this[LedgerUtxosTable.slotCreated] = slotNumber
            this[LedgerUtxosTable.blockSpent] = null
            this[LedgerUtxosTable.slotSpent] = null
        }.map { row -> row[LedgerUtxosTable.id].value }
        val createdUtxoToLedgerUtxoIdMap = createdUtxos.zip(ledgerUtxoTableInsertedIds).toMap()
//        }

        // figure out which LedgerAssets already exist
//        val queryLedgerAssetTime = measureTimeMillis {
        createdUtxos.forEach { createdUtxo ->
            createdUtxo.nativeAssets.forEach { nativeAsset ->
                val ledgerAssetTableId = ledgerAssetsTableIdCache[Pair(nativeAsset.policy, nativeAsset.name)]
                ledgerAssetTableId?.let { nativeAssetToLedgerAssetIdMap[nativeAsset] = it }
                    ?: nativeAssetsToInsert.add(
                        nativeAsset
                    )
            }
        }
//        }

        // batch insert into ledger_assets table those that don't already exist
//        val insertLedgerAssetTime = measureTimeMillis {
        val ledgerAssetInsertedIds = LedgerAssetsTable.batchInsert(nativeAssetsToInsert) { nativeAsset ->
            this[LedgerAssetsTable.policy] = nativeAsset.policy
            this[LedgerAssetsTable.name] = nativeAsset.name
            this[LedgerAssetsTable.image] = ""
            this[LedgerAssetsTable.description] = null
        }.map { row -> row[LedgerAssetsTable.id].value }
        nativeAssetsToInsert.zip(ledgerAssetInsertedIds)
            .forEach { (nativeAsset, ledgerAssetId) -> nativeAssetToLedgerAssetIdMap[nativeAsset] = ledgerAssetId }
        ledgerAssetsTableIdCache.invalidateAll(nativeAssetsToInsert.map { Pair(it.policy, it.name) })
//        }

        // batch insert into ledger_utxo_assets table
//        val insertLedgerUtxoAssetTime = measureTimeMillis {
        LedgerUtxoAssetsTable.batchInsert(
            createdUtxos.flatMap { createdUtxo ->
                createdUtxo.nativeAssets.map { nativeAsset ->
                    Triple(
                        createdUtxoToLedgerUtxoIdMap[createdUtxo]!!,
                        nativeAssetToLedgerAssetIdMap[nativeAsset]!!,
                        nativeAsset.amount.toString()
                    )
                }
            }
        ) { triple ->
            this[LedgerUtxoAssetsTable.ledgerUtxoId] = triple.first
            this[LedgerUtxoAssetsTable.ledgerAssetId] = triple.second
            this[LedgerUtxoAssetsTable.amount] = triple.third
        }
//        }
//        val totalTime =
//            queryAddressTime + insertAddressTime + insertUtxoTime + queryLedgerAssetTime + insertLedgerAssetTime + insertLedgerUtxoAssetTime
//        log.warn("createUtxos(blockNumber=$blockNumber,...): total: ${totalTime}ms, queryAddressTime: ${queryAddressTime}ms, insertAddressTime: ${insertAddressTime}ms, insertUtxoTime: ${insertUtxoTime}ms, queryLedgerAssetTime: ${queryLedgerAssetTime}ms, insertLedgerAssetTime: ${insertLedgerAssetTime}ms, insertLedgerUtxoAssetTime: ${insertLedgerUtxoAssetTime}ms")
    }

    private const val ADA_HANDLES_POLICY = "f0ff48bbb7bbe9d59a40f1ce90e9e9d0ff5002ec48f232b49ca0fb9a"
    //private const val ADA_HANDLES_POLICY = "c071f4c41ed451ab55da30e570edb868a1219fb7fe0087b3b8f19f0b"

    // test - AwesomePoolToken6
    fun queryAdaHandle(adaHandleName: String): String? = transaction {
        LedgerTable
            .innerJoin(
                otherTable = LedgerUtxosTable,
                onColumn = { LedgerTable.id },
                otherColumn = { ledgerId }
            )
            .innerJoin(
                otherTable = LedgerUtxoAssetsTable,
                onColumn = { LedgerUtxosTable.id },
                otherColumn = { ledgerUtxoId }
            )
            .innerJoin(
                otherTable = LedgerAssetsTable,
                onColumn = { LedgerUtxoAssetsTable.ledgerAssetId },
                otherColumn = { LedgerAssetsTable.id }
            )
            .slice(LedgerTable.address)
            .select {
                (LedgerAssetsTable.policy eq ADA_HANDLES_POLICY) and
                        (LedgerAssetsTable.name eq adaHandleName.toByteArray().toHexString()) and
                        LedgerUtxosTable.blockSpent.isNull()
            }
            .firstOrNull()?.let { row -> row[LedgerTable.address] }
    }

    private val idCount = LedgerUtxosTable.id.count()
    private val siblingHashCountCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .build<String, Long> { hash ->
            transaction {
                LedgerUtxosTable.slice(idCount).select { LedgerUtxosTable.txId eq hash }.first()[idCount]
            }
        }

    fun siblingHashCount(hash: String): Long = siblingHashCountCache[hash]!!

    fun insertBlockFetch(blockNumber: Long, slotNumber: Long, hash: String, prevHash: String) {
        BlockFetchTable.insert { row ->
            row[BlockFetchTable.blockNumber] = blockNumber
            row[BlockFetchTable.slotNumber] = slotNumber
            row[BlockFetchTable.hash] = hash
            row[BlockFetchTable.prevHash] = prevHash
        }
    }

    fun findTipBlock(): BlockFetch? = transaction {
        BlockFetchTable
            .selectAll()
            .orderBy(BlockFetchTable.blockNumber, SortOrder.DESC)
            .limit(1)
            .firstOrNull()?.let { row ->
                BlockFetch(
                    id = row[BlockFetchTable.id].value,
                    blockNumber = row[BlockFetchTable.blockNumber],
                    slotNumber = row[BlockFetchTable.slotNumber],
                    hash = row[BlockFetchTable.hash],
                    prevHash = row[BlockFetchTable.prevHash],
                )
            }
    }
}