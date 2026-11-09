package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.DecodedOutput
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.services.SubmittedTransactionDecoder
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Only acknowledged submissions affect the live wallet; nothing is restored from disk. */
@Component
class LedgerDao(
    private val cardano: CardanoRepository,
    private val json: ObjectMapper,
    private val jdbc: JdbcTemplate,
    @param:Qualifier("refreshWalletChannel") private val refresh: MutableStateFlow<Long?>,
) {
    private data class Submitted(
        val network: String, val host: Host, val node: Node, val era: String,
        val envelope: String, val transaction: SubmittedTransactionDecoder.Submitted,
        val at: Instant = Instant.now(), var includedAt: BigInteger? = null,
    )

    private val submitted = LinkedHashMap<Pair<String, String>, Submitted>()
    private val log = LoggerFactory.getLogger(LedgerDao::class.java)

    @Synchronized
    fun <T> withSubmissionLock(action: () -> T): T = action()

    @Synchronized
    fun recordSubmitted(host: Host, node: Node, era: String, txId: String, signedEnvelope: String) {
        val transaction = SubmittedTransactionDecoder.decodeSubmitted(json.readTree(signedEnvelope).path("cborHex").asString(), era)
        require(transaction.id == txId.lowercase()) { "Submitted transaction ID does not match signed bytes" }
        val network = cardano.networkKey(node)
        submitted[network to transaction.id] = Submitted(network, host, node, era, signedEnvelope, transaction)
        prune()
        try {
            jdbc.update("INSERT INTO transactions (id,txid,at) VALUES (nextval('hibernate_sequence'),?,?)", transaction.id, Instant.now().toString())
        } catch (e: Exception) {
            // Submission already succeeded; a history-write failure must not invite another payment.
            log.error("Transaction {} submitted but its history record could not be saved", transaction.id, e)
        }
        refresh.update { (it ?: 0L) + 1 }
    }

    private fun prune() {
        val cutoff = Instant.now().minusSeconds(86_400)
        submitted.entries.removeIf { it.value.at.isBefore(cutoff) }
        while (submitted.size > 100) submitted.remove(submitted.keys.first())
    }

    @Synchronized
    fun liveUtxos(network: String, address: String, confirmed: List<Utxo>): List<Utxo> {
        prune()
        val pending = submitted.values.filter { it.network == network && it.includedAt == null }
        val spent = pending.flatMap { it.transaction.normalInputs }.toHashSet()
        val outputs = LinkedHashMap<Pair<String, Int>, Utxo>()
        confirmed.forEach { outputs[it.hash to Math.toIntExact(it.ix)] = it }
        pending.forEach { entry ->
            entry.transaction.outputs.forEach { (reference, output) ->
                if (LedgerRepository.addressText(output.address) == address) {
                    outputs[reference] = Utxo(reference.first, reference.second.toLong(), output.coin,
                        output.assets.map { NativeAsset(it.name.toHexString(), it.policy.toHexString(), it.quantity) })
                }
            }
        }
        return outputs.filterKeys { it !in spent }.values.toList()
    }

    @Synchronized
    fun pendingOutput(network: String, hash: String, index: Int): DecodedOutput? {
        prune()
        return submitted.values.firstNotNullOfOrNull {
            if (it.network == network && it.includedAt == null) it.transaction.outputs[hash to index] else null
        }
    }

    @Synchronized
    fun confirmed(network: String, slot: BigInteger, transactionIds: Collection<String>) {
        transactionIds.forEach { submitted[network to it]?.includedAt = slot }
    }

    @Synchronized
    fun rollback(network: String, slot: BigInteger?) {
        prune()
        val rolledBack = submitted.values.filter { it.network == network && it.includedAt?.let { at -> slot == null || at > slot } == true }
        if (rolledBack.isEmpty()) return
        rolledBack.forEach { it.includedAt = null }
        // Retain baseline rollback replay, in submission order, for acknowledged transactions only.
        submitted.values.filter { it.network == network && it.includedAt == null }.forEach { entry ->
            val path = "/tmp/jormanager-rollback-${UUID.randomUUID()}.txsigned"
            val connection = HostConnection(entry.host, entry.node)
            try {
                if (cardano.networkKey(entry.node) != network) return@forEach
                connection.commandWriteFile(path, entry.envelope)
                connection.commandArgs(listOf(entry.host.cardanoCliPath, entry.era, "transaction", "submit", "--tx-file", path) +
                    cardano.networkArguments(entry.node) + listOf("--socket-path", "${entry.host.nodeHomePath}/${entry.node.name}/db/socket"))
            } catch (e: Exception) {
                log.warn("Unable to resubmit acknowledged transaction {} after rollback", entry.transaction.id, e)
            } finally {
                try { connection.commandArgs(listOf("rm", "-f", path)) } catch (e: Exception) {
                    log.warn("Unable to remove rollback transaction file {}", path, e)
                }
            }
        }
        refresh.update { (it ?: 0L) + 1 }
    }
}
