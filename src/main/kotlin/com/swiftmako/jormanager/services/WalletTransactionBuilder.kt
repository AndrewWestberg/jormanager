package com.swiftmako.jormanager.services

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborByteString
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.utils.CborSpan
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.math.BigInteger

@Service
class WalletTransactionBuilder(private val json: ObjectMapper) {
    data class Output(val address: String, val lovelace: BigInteger, val nativeAssets: List<NativeAsset> = emptyList())

    fun build(
        host: Host, connection: HostConnection, era: String, inputs: List<Utxo>, outputs: List<Output>,
        changeAddress: String, withdrawals: BigInteger = BigInteger.ZERO, deposits: BigInteger = BigInteger.ZERO,
        refunds: BigInteger = BigInteger.ZERO, extraArgs: List<String> = emptyList(), validFrom: Long? = null,
        validUntil: Long? = null, protocolParameters: String, protocolPath: String, witnessCount: Int, bodyPath: String,
    ): ReviewedWalletOperations.CliBody {
        val eraNumber = QueryTip(era).eraNumber
        require(eraNumber in QueryTip.ERA_SHELLEY..QueryTip.ERA_DIJKSTRA) { "Unsupported transaction era" }
        require(inputs.isNotEmpty() && witnessCount > 0 && changeAddress.isNotBlank()) { "Invalid transaction inputs or witnesses" }
        require(listOf(withdrawals, deposits, refunds).all { it.signum() >= 0 }) { "Invalid transaction balance" }
        require(validFrom == null || validFrom >= 0)
        require(validUntil == null || validUntil >= 0)
        require(validFrom == null || validUntil == null || validFrom <= validUntil)
        require(inputs.all { it.hash.matches(Regex("[0-9a-fA-F]{64}")) && it.ix in 0..65535 && it.lovelace.signum() >= 0 }) { "Invalid input" }
        require(inputs.map { it.hash.lowercase() to it.ix }.toSet().size == inputs.size) { "Duplicate transaction input" }
        require(outputs.all { it.address.isNotBlank() && it.lovelace.signum() >= 0 }) { "Invalid output" }

        fun addAssets(target: MutableMap<Pair<String, String>, BigInteger>, assets: List<NativeAsset>, sign: Int) {
            val seen = HashSet<Pair<String, String>>()
            for (asset in assets) {
                require(asset.policy.matches(Regex("[0-9a-fA-F]{56}")) && asset.name.matches(Regex("([0-9a-fA-F]{2}){0,32}")) && asset.amount.signum() > 0) { "Invalid native asset" }
                val key = asset.policy.lowercase() to asset.name.lowercase()
                require(seen.add(key)) { "Duplicate native asset" }
                target[key] = (target[key] ?: BigInteger.ZERO) + asset.amount * BigInteger.valueOf(sign.toLong())
            }
        }
        val assets = sortedMapOf<Pair<String, String>, BigInteger>(compareBy({ it.first }, { it.second }))
        inputs.forEach { addAssets(assets, it.nativeAssets, 1) }
        outputs.forEach { addAssets(assets, it.nativeAssets, -1) }
        require(assets.values.all { it.signum() >= 0 }) { "Insufficient native assets" }
        val changeAssets = assets.filterValues { it.signum() > 0 }.map { (key, amount) -> NativeAsset(key.second, key.first, amount) }
        val available = inputs.fold(withdrawals + refunds - deposits) { sum, input -> sum + input.lovelace } - outputs.fold(BigInteger.ZERO) { sum, output -> sum + output.lovelace }
        require(available.signum() >= 0) { "Insufficient funds; reduce the requested amount or add funds" }
        val parameters = json.readTree(protocolParameters)
        fun limit(name: String, required: Boolean): BigInteger? {
            val node = parameters.path(name)
            if (node.isMissingNode || node.isNull) {
                require(!required) { "Missing protocol parameter $name" }
                return null
            }
            val value = node.asString().toBigIntegerOrNull()
            require(value != null && value.signum() > 0) { "Invalid protocol parameter $name" }
            return value
        }
        val maxTxSize = requireNotNull(limit("maxTxSize", true))
        val maxValueSize = limit("maxValueSize", eraNumber >= QueryTip.ERA_ALONZO)
        fun outputArg(output: Output): String = output.address + "+" + output.lovelace + output.nativeAssets.joinToString("") {
            "+${it.amount} ${it.policy.lowercase()}${if (it.name.isEmpty()) "" else ".${it.name.lowercase()}"}"
        }
        fun command(vararg args: String) = connection.commandArgs(listOf(host.cardanoCliPath, era, "transaction") + args)
        fun amount(text: String): BigInteger {
            val token = text.trim().split(Regex("\\s+")).firstOrNull { it.matches(Regex("[0-9]+")) }
            return requireNotNull(token) { "Invalid CLI amount" }.toBigInteger()
        }
        fun minimum(output: Output): BigInteger = amount(command("calculate-min-required-utxo", "--protocol-params-file", protocolPath, "--tx-out", outputArg(output)))
        outputs.forEach { require(it.lovelace >= minimum(it)) { "Recipient output is below minimum ADA" } }
        var fee = BigInteger.ZERO
        repeat(16) {
            val change = available - fee
            require(change.signum() >= 0) { "Insufficient funds for fee; reduce the requested amount or add funds" }
            val finalOutputs = outputs.toMutableList()
            if (change.signum() != 0 || changeAssets.isNotEmpty()) {
                val output = Output(changeAddress, change, changeAssets)
                finalOutputs.add(output)
            }
            val args = mutableListOf("build-raw")
            inputs.forEach { args += listOf("--tx-in", "${it.hash}#${it.ix}") }
            finalOutputs.forEach { args += listOf("--tx-out", outputArg(it)) }
            args += extraArgs
            validFrom?.let { args += listOf("--invalid-before", it.toString()) }
            validUntil?.let { args += listOf("--invalid-hereafter", it.toString()) }
            args += listOf("--fee", fee.toString(), "--out-file", bodyPath)
            command(*args.toTypedArray())
            val requiredFee = amount(command("calculate-min-fee", "--tx-body-file", bodyPath, "--protocol-params-file", protocolPath,
                "--witness-count", witnessCount.toString(), "--byron-witness-count", "0", "--reference-script-size", "0", "--output-text"))
            if (requiredFee <= fee) {
                val text = connection.commandReadFile(bodyPath)
                val cbor = json.readTree(text).path("cborHex").asString()
                val decoded = ReviewedTransactionIdentity.decodeSubmitted(cbor, era)
                if (change.signum() != 0 || changeAssets.isNotEmpty()) {
                    require(change >= minimum(finalOutputs.last())) { "Insufficient change ADA; reduce the requested amount or add funds" }
                }
                val actualFee = decoded.body.entries().single { it.first.integer() == BigInteger.TWO }.second.integer()
                require(actualFee == fee) { "Built transaction fee differs from balanced fee" }
                maxValueSize?.let { limit -> decoded.batch.parent.outputs.forEach { output ->
                    val raw = output.raw
                    val value: CborSpan = if (raw.major == 4) raw.array()[1] else raw.entries().single { it.first.integer() == BigInteger.ONE }.second
                    require(BigInteger.valueOf((value.end - value.start).toLong()) <= limit) { "Transaction output exceeds maximum value size" }
                } }
                val witnessArray = CborArray.create()
                repeat(witnessCount) {
                    witnessArray.add(CborArray.create().apply {
                        add(CborByteString.create(ByteArray(32)))
                        add(CborByteString.create(ByteArray(64)))
                    })
                }
                val witnessSize = 2L + witnessArray.toCborByteArray().size + if (eraNumber >= QueryTip.ERA_CONWAY) 3 else 0
                val auxiliary = decoded.batch.parent.auxiliaryData
                val auxiliarySize = auxiliary?.let { it.end - it.start } ?: 1
                val envelopeSize = 1L + (decoded.body.end - decoded.body.start) + witnessSize + auxiliarySize +
                    if (eraNumber in QueryTip.ERA_ALONZO..QueryTip.ERA_CONWAY) 1 else 0
                require(BigInteger.valueOf(envelopeSize) <= maxTxSize) { "Transaction exceeds maximum transaction size" }
                val txId = command("txid", "--tx-body-file", bodyPath, "--output-text").trim().lowercase()
                require(txId == decoded.id) { "CLI transaction ID does not match transaction body" }
                return ReviewedWalletOperations.CliBody(text, txId)
            }
            fee = fee.max(requiredFee)
        }
        throw IllegalArgumentException("Transaction fee did not converge after 16 iterations")
    }
}
