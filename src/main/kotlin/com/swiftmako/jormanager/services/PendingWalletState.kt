package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.ChainReadState
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.LedgerRepository
import com.swiftmako.jormanager.utils.Bech32
import java.math.BigInteger
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** A local-submission overlay, never a substitute for chain adoption. */
@Service
class PendingWalletState(
    private val ledger: LedgerDao,
    private val coordinator: ChainIngestionCoordinator,
    private val cardano: CardanoRepository,
    private val json: ObjectMapper,
) {
    data class Operation(val id: String, val networkKey: String, val txId: String, val era: String, val state: String,
                         val signedBatch: String?, val validFrom: Long?, val validUntil: Long?)
    data class Reservation(val operationId: String, val txId: String, val index: Int, val state: String)
    data class DatabaseSnapshot(val readState: ChainReadState, val utxosByAddress: Map<String, List<Utxo>>,
                                val operations: List<Operation>, val reservations: List<Reservation>)
    data class PendingOutput(val address: String, val utxo: Utxo, val referenceScript: ByteArray?)
    data class Snapshot(val readState: ChainReadState, val utxosByAddress: Map<String, List<Utxo>>,
                        val pendingOutputs: Map<Pair<String, Int>, PendingOutput>,
                        val reservedRewardWithdrawals: Map<String, BigInteger>)

    fun snapshot(host: Host, node: Node, connection: HostConnection, magic: String, socket: String, era: String,
                 addresses: Set<String>, excludingOperationId: String? = null): Snapshot {
        require(host.id == node.hostId && socket == "--socket-path ${host.nodeHomePath}/${node.name}/db/socket") { "Wallet target changed" }
        check(cardano.getEra(host, node, magic, socket).equals(era, true)) { "Wallet target era changed" }
        val network = cardano.networkKey(node)
        val evidence = cardano.evidence(node)
        check(evidence.networkKey == network && evidence.era.equals(era, true) && evidence.freshness == "current" &&
            evidence.source == "host:${host.id}/node:${node.id}") { "Wallet target era unavailable" }
        val database = ledger.querySnapshot(network, addresses)
        if (!database.readState.walletReady) throw LedgerDao.WalletIndexUnavailableException(database.readState)
        val decoded = decode(database.operations)
        val memberOwners = decoded.flatMap { (id, tx) ->
            (listOf(tx.batch.parent) + tx.batch.children).map { it.bodyId.toHexString().lowercase() to id }
        }.toMap()
        val relevant = mutableSetOf<String>()
        val queue = ArrayDeque(database.operations.filter { it.state == "submitted" && it.id != excludingOperationId }.map { it.id })
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!relevant.add(id)) continue
            decoded[id]?.normalInputs?.forEach { ref -> memberOwners[ref.first.lowercase()]?.let(queue::addLast) }
        }
        val queryAddresses = addresses + relevant.flatMap { id ->
            decoded[id]?.outputs?.values?.map { LedgerRepository.addressText(it.address) }.orEmpty()
        }
        val flags = magic.split(' ').filter(String::isNotBlank) + socket.split(' ').filter(String::isNotBlank)
        fun query(vararg args: String): JsonNode = json.readTree(connection.commandArgs(
            listOf(host.cardanoCliPath, era, "query") + args + flags + "--output-json"))
        val tip = query("tip")
        val slot = number(tip.path("slot"))
        require(slot.signum() >= 0 && tip.path("hash").asString().matches(Regex("[0-9a-fA-F]{64}")) &&
            tip.path("era").asString().equals(era, true)) { "Invalid node tip" }
        var observationSlot = slot
        val inMempool = mutableMapOf<String, Boolean>()
        database.operations.filter { it.state == "submitted" && it.id != excludingOperationId }.forEach { op ->
            require(op.txId.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid stored transaction ID" }
            val result = json.readTree(connection.commandArgs(listOf(host.cardanoCliPath, era, "query", "tx-mempool") +
                flags + listOf("--output-json", "tx-exists", op.txId)))
            require(result.isObject && result.path("exists").isBoolean && result.path("txId").asString().equals(op.txId, true) &&
                number(result.path("slot")) >= slot) { "Invalid or stale mempool response" }
            observationSlot = observationSlot.max(number(result.path("slot")))
            inMempool[op.id] = result.path("exists").asBoolean()
        }
        val nodeOutputs = mutableMapOf<Pair<String, Int>, PendingOutput>()
        for (address in queryAddresses) {
            val response = query("utxo", "--address", address)
            require(response.isObject) { "Invalid node UTxO response" }
            response.properties().forEach { entry ->
                val parts = entry.key.split('#')
                require(parts.size == 2 && parts[0].matches(Regex("[0-9a-fA-F]{64}")) && parts[1].matches(Regex("0|[1-9][0-9]*")))
                val index = parts[1].toInt()
                require(index in 0..65535) { "Invalid output index" }
                val ref = parts[0].lowercase() to index
                val output = entry.value
                require(output.isObject && output.path("address").isString && output.path("address").asString() == address)
                val value = output.path("value")
                require(value.isObject && value.has("lovelace"))
                val coin = number(value.path("lovelace"))
                require(coin.signum() >= 0)
                val assets = mutableListOf<NativeAsset>()
                value.properties().filter { it.key != "lovelace" }.forEach { policy ->
                    require(policy.key.matches(Regex("[0-9a-fA-F]{56}")) && policy.value.isObject)
                    policy.value.properties().forEach { asset ->
                        require(asset.key.matches(Regex("(?:[0-9a-fA-F]{2}){0,32}")))
                        val quantity = number(asset.value)
                        require(quantity.signum() > 0)
                        assets += NativeAsset(asset.key.lowercase(), policy.key.lowercase(), quantity)
                    }
                }
                require(nodeOutputs.put(ref, PendingOutput(address, Utxo(ref.first, index.toLong(), coin, assets), null)) == null)
            }
        }
        val result = merge(database, addresses, nodeOutputs, inMempool, observationSlot, excludingOperationId, decoded)
        val current = coordinator.status(network)
        if (!current.walletReady) throw LedgerDao.WalletIndexUnavailableException(current)
        check(current.generation == database.readState.generation && cardano.networkKey(node) == network) { "Wallet snapshot target or generation changed" }
        return result
    }

    companion object {
        private val unresolved = setOf("previewed", "signing", "submission_uncertain", "submitted", "rolled_back")
        private fun decode(operations: List<Operation>): Map<String, ReviewedTransactionIdentity.Submitted> =
            operations.filter { it.signedBatch != null }.associate { op ->
                val transaction = ReviewedTransactionIdentity.decodeSubmitted(op.signedBatch!!, op.era)
                require(transaction.id.equals(op.txId, true)) { "Signed transaction ID differs from operation" }
                require(transaction.batch.parent.fields[8]?.integer()?.longValueExact() == op.validFrom &&
                    transaction.batch.parent.fields[3]?.integer()?.longValueExact() == op.validUntil) { "Signed validity differs from operation" }
                op.id to transaction
            }
        private fun number(value: JsonNode): BigInteger {
            require(value.isIntegralNumber || value.isString) { "Invalid integer" }
            val text = value.asString()
            require(text.matches(Regex("0|[1-9][0-9]*"))) { "Invalid integer" }
            return text.toBigInteger()
        }
        private fun amounts(utxo: Utxo): Map<Pair<String, String>, BigInteger> {
            val result = mutableMapOf<Pair<String, String>, BigInteger>()
            for (asset in utxo.nativeAssets) {
                val key = asset.policy.lowercase() to asset.name.lowercase()
                require(asset.amount.signum() > 0 && result.put(key, asset.amount) == null) { "Invalid output assets" }
            }
            return result
        }
        private fun canonical(utxo: Utxo): Utxo = utxo.copy(
            hash = utxo.hash.lowercase(),
            nativeAssets = utxo.nativeAssets.map { it.copy(policy = it.policy.lowercase(), name = it.name.lowercase()) }
                .sortedWith(compareBy({ it.policy }, { it.name })),
        )
        private fun same(a: PendingOutput, b: PendingOutput): Boolean = a.address == b.address &&
            a.utxo.hash.equals(b.utxo.hash, true) && a.utxo.ix == b.utxo.ix && a.utxo.lovelace == b.utxo.lovelace &&
            amounts(a.utxo) == amounts(b.utxo)

        internal fun merge(database: DatabaseSnapshot, addresses: Set<String>, nodeOutputs: Map<Pair<String, Int>, PendingOutput>,
                           inMempool: Map<String, Boolean>, slot: BigInteger, excludingOperationId: String? = null,
                           decoded: Map<String, ReviewedTransactionIdentity.Submitted> = decode(database.operations)): Snapshot {
            val network = database.readState.networkKey
            require(database.readState.walletReady && slot.signum() >= 0)
            val operations = database.operations
            require(operations.all { it.networkKey == network } && operations.map { it.id }.toSet().size == operations.size) { "Inconsistent operation network" }
            require(operations.filter { it.state == "submitted" }.all { it.id in decoded }) { "Submitted operation has no signed transaction" }
            val owners = mutableMapOf<String, String>()
            val members = mutableMapOf<String, com.swiftmako.jormanager.model.DecodedTransactionMember>()
            val outputs = mutableMapOf<Pair<String, Int>, PendingOutput>()
            for (op in operations) {
                val tx = decoded[op.id] ?: continue
                for (member in listOf(tx.batch.parent) + tx.batch.children) {
                    val id = member.bodyId.toHexString().lowercase()
                    require(owners.put(id, op.id) == null) { "Duplicate local transaction member" }
                    members[id] = member
                }
                for ((ref, output) in tx.outputs) {
                    val address = LedgerRepository.addressText(output.address)
                    val assets = output.assets.map { NativeAsset(it.name.toHexString(), it.policy.toHexString(), it.quantity) }
                    val candidate = PendingOutput(address, canonical(Utxo(ref.first.lowercase(), ref.second.toLong(), output.coin, assets)), output.referenceScript?.tagged(24)?.payload())
                    require(candidate.utxo.lovelace.signum() >= 0 && amounts(candidate.utxo).size == assets.size)
                    require(outputs.put(ref.first.lowercase() to ref.second, candidate) == null) { "Duplicate local output" }
                }
            }
            val byId = operations.associateBy { it.id }
            val visiting = mutableSetOf<String>()
            val accepted = mutableMapOf<String, Boolean>()
            fun usable(memberId: String): Boolean {
                accepted[memberId]?.let { return it }
                require(visiting.add(memberId)) { "Cyclic local transaction dependencies" }
                val op = byId.getValue(owners.getValue(memberId))
                val member = members.getValue(memberId)
                val dependencies = member.inputs.mapNotNull { input ->
                    val ref = input.transactionId.toHexString().lowercase() to input.index
                    owners[ref.first]?.let {
                        require(outputs.containsKey(ref)) { "Invalid local dependency" }
                        ref
                    }
                }.distinct()
                val dependenciesReady = dependencies.map { ref ->
                    val parentReady = usable(ref.first)
                    val chain = nodeOutputs[ref]
                    if (chain != null) require(same(outputs.getValue(ref), chain)) { "Conflicting confirmed output" }
                    parentReady || chain != null
                }.all { it }
                val ready = op.id != excludingOperationId && (op.state == "confirmed" || (op.state == "submitted" && inMempool[op.id] == true &&
                    (op.validFrom == null || slot >= BigInteger.valueOf(op.validFrom)) &&
                    (op.validUntil == null || slot < BigInteger.valueOf(op.validUntil)) && dependenciesReady))
                visiting.remove(memberId)
                accepted[memberId] = ready
                return ready
            }
            members.keys.forEach { usable(it) }
            val withheld = mutableSetOf<Pair<String, Int>>()
            val reservationRefs = mutableSetOf<Pair<String, Int>>()
            database.reservations.forEach {
                require(it.index >= 0 && it.txId.matches(Regex("[0-9a-fA-F]{64}")) &&
                    byId[it.operationId]?.state == it.state) { "Invalid reservation" }
                val ref = it.txId.lowercase() to it.index
                require(reservationRefs.add(ref)) { "Duplicate reservation" }
                if (it.operationId != excludingOperationId && it.state in unresolved) withheld += ref
            }
            val rewards = mutableMapOf<String, BigInteger>()
            for (op in operations.filter { it.id != excludingOperationId && it.state in unresolved }) {
                val tx = decoded[op.id] ?: continue
                withheld += tx.inputs.map { it.first.lowercase() to it.second }
                for (member in listOf(tx.batch.parent) + tx.batch.children) for (withdrawal in member.withdrawals) {
                    require(withdrawal.address.isNotEmpty() && withdrawal.amount.signum() >= 0)
                    val hrp = if ((withdrawal.address[0].toInt() and 15) == 1) "stake" else "stake_test"
                    val address = Bech32.encode(hrp, withdrawal.address)
                    rewards[address] = (rewards[address] ?: BigInteger.ZERO) + withdrawal.amount
                }
            }
            val merged = mutableMapOf<Pair<String, Int>, PendingOutput>()
            database.utxosByAddress.forEach { (address, values) ->
                require(address in addresses)
                values.forEach { value ->
                    require(value.ix in 0..65535 && value.lovelace.signum() >= 0)
                    val ref = value.hash.lowercase() to value.ix.toInt()
                    val chain = nodeOutputs[ref]
                    val indexed = PendingOutput(address, canonical(value), null)
                    if (chain != null) {
                        require(same(indexed, chain)) { "Indexed output differs from node" }
                        require(merged.put(ref, indexed) == null) { "Duplicate indexed output" }
                    }
                }
            }
            val pending = mutableMapOf<Pair<String, Int>, PendingOutput>()
            for (op in operations.filter { it.id != excludingOperationId && it.state == "submitted" }) {
                val tx = decoded[op.id] ?: continue
                for ((rawRef, _) in tx.outputs) {
                    val ref = rawRef.first.lowercase() to rawRef.second
                    val output = outputs.getValue(ref)
                    val chain = nodeOutputs[ref]
                    if (chain != null) require(same(output, chain)) { "Pending output differs from node" }
                    if (accepted[ref.first] != true && chain == null) continue
                    if (output.address !in addresses) continue
                    val previous = merged[ref]
                    if (previous != null) require(same(previous, output)) { "Conflicting duplicate output" }
                    else merged[ref] = output
                    pending[ref] = output
                }
                if ((listOf(tx.batch.parent) + tx.batch.children).any { accepted[it.bodyId.toHexString().lowercase()] == true })
                    withheld += tx.normalInputs.map { it.first.lowercase() to it.second }
            }
            withheld.forEach { merged.remove(it); pending.remove(it) }
            return Snapshot(database.readState, addresses.associateWith { address ->
                merged.values.filter { it.address == address }.map { it.utxo }.sortedWith(compareBy({ it.hash }, { it.ix }))
            }, pending, rewards)
        }
    }
}
