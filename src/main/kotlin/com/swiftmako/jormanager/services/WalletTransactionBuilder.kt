package com.swiftmako.jormanager.services

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborByteString
import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.PlannedOutput
import com.swiftmako.jormanager.model.ProtocolParameters
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.utils.CborSpan
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.math.BigInteger

@Service
class WalletTransactionBuilder(
    private val json: ObjectMapper,
    private val protocolParamsAdapter: JsonAdapter<ProtocolParameters>,
    private val planner: TransactionPlannerService = TransactionPlannerService(),
) {
    fun selectAdminInputs(
        candidates: List<Utxo>,
        changeAddress: String,
        protocolParameters: String,
        validUntil: Long,
        witnessCount: Int,
        extraPayloadBytes: Int,
        netDeposit: BigInteger = BigInteger.ZERO,
    ): List<Utxo> {
        if (candidates.size <= 1) return candidates
        return planner
            .planAdminTransaction(
                utxos = candidates,
                changeAddress = changeAddress,
                protocolParameters = requireNotNull(protocolParamsAdapter.fromJson(protocolParameters)),
                deposit = netDeposit.max(BigInteger.ZERO),
                depositRefund = netDeposit.negate().max(BigInteger.ZERO),
                ttl = validUntil,
                witnessCount = witnessCount,
                extraPayloadBytes = extraPayloadBytes,
            ).selectedUtxos
    }

    data class Output(
        val address: String,
        val lovelace: BigInteger,
        val nativeAssets: List<NativeAsset> = emptyList()
    )

    data class CliBody(
        val textEnvelope: String,
        val txId: String,
        val fee: BigInteger = BigInteger.ZERO,
        val outputCount: Int = 0,
        val estimatedTxSize: Int = 0
    )

    fun build(
        host: Host,
        connection: HostConnection,
        era: String,
        inputs: List<Utxo>,
        outputs: List<Output>,
        changeAddress: String,
        withdrawals: BigInteger = BigInteger.ZERO,
        deposits: BigInteger = BigInteger.ZERO,
        refunds: BigInteger = BigInteger.ZERO,
        extraArgs: List<String> = emptyList(),
        validFrom: Long? = null,
        validUntil: Long? = null,
        protocolParameters: String,
        protocolPath: String,
        witnessCount: Int,
        bodyPath: String,
        referenceScriptSize: Long = 0,
        outputsForFee: ((BigInteger) -> List<Output>)? = null,
        changeLayout: List<PlannedOutput>? = null,
    ): CliBody {
        val eraNumber = QueryTip(era).eraNumber
        require(eraNumber in QueryTip.ERA_SHELLEY..QueryTip.ERA_DIJKSTRA) { "Unsupported transaction era" }
        require(inputs.isNotEmpty() && witnessCount > 0 && changeAddress.isNotBlank()) { "Invalid transaction inputs or witnesses" }
        require(referenceScriptSize >= 0) { "Invalid reference script size" }
        require(listOf(withdrawals, deposits, refunds).all { it.signum() >= 0 }) { "Invalid transaction balance" }
        require(validFrom == null || validFrom >= 0)
        require(validUntil == null || validUntil >= 0)
        require(validFrom == null || validUntil == null || validFrom <= validUntil)
        require(inputs.all { it.hash.matches(Regex("[0-9a-fA-F]{64}")) && it.ix in 0..65535 && it.lovelace.signum() >= 0 }) { "Invalid input" }
        require(inputs.map { it.hash.lowercase() to it.ix }.toSet().size == inputs.size) { "Duplicate transaction input" }

        fun addAssets(
            target: MutableMap<Pair<String, String>, BigInteger>,
            assets: List<NativeAsset>,
            sign: Int
        ) {
            val seen = HashSet<Pair<String, String>>()
            for (asset in assets) {
                require(asset.policy.matches(Regex("[0-9a-fA-F]{56}")) && asset.name.matches(Regex("([0-9a-fA-F]{2}){0,32}")) && asset.amount.signum() > 0) { "Invalid native asset" }
                val key = asset.policy.lowercase() to asset.name.lowercase()
                require(seen.add(key)) { "Duplicate native asset" }
                target[key] = (target[key] ?: BigInteger.ZERO) + asset.amount * BigInteger.valueOf(sign.toLong())
            }
        }
        val parameters = json.readTree(protocolParameters)

        fun limit(
            name: String,
            required: Boolean
        ): BigInteger? {
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

        fun outputArg(output: Output): String =
            output.address + "+" + output.lovelace + output.nativeAssets.joinToString("") {
                "+${it.amount} ${it.policy.lowercase()}${if (it.name.isEmpty()) "" else ".${it.name.lowercase()}"}"
            }

        fun command(vararg args: String) = connection.commandArgs(listOf(host.cardanoCliPath, era, "transaction") + args)

        fun amount(text: String): BigInteger {
            val token = text.trim().split(Regex("\\s+")).firstOrNull { it.matches(Regex("[0-9]+")) }
            return requireNotNull(token) { "Invalid CLI amount" }.toBigInteger()
        }

        fun minimum(output: Output): BigInteger = amount(command("calculate-min-required-utxo", "--protocol-params-file", protocolPath, "--tx-out", outputArg(output)))

        fun plannedChange(
            lovelace: BigInteger,
            assets: List<NativeAsset>
        ): List<Output> {
            val layout = requireNotNull(changeLayout)
            val remainingAssets = assets.associateBy { it.policy to it.name }.toMutableMap()
            val tokenOutputs = layout
                .mapNotNull { planned ->
                    val group = planned.nativeAssets.mapNotNull { remainingAssets.remove(it.policy.lowercase() to it.name.lowercase()) }
                    if (group.isEmpty()) null else Output(changeAddress, minimum(Output(changeAddress, planned.lovelace, group)), group)
                }.toMutableList()
            require(remainingAssets.isEmpty()) { "Selected inputs differ from planned change assets" }

            val cleanAda = lovelace - tokenOutputs.fold(BigInteger.ZERO) { total, output -> total + output.lovelace }
            require(cleanAda.signum() >= 0) { "Insufficient change ADA; reduce the requested amount or add funds" }
            if (cleanAda.signum() == 0) return tokenOutputs
            val half = cleanAda / BigInteger.TWO
            if (layout.count { it.nativeAssets.isEmpty() } > 1 && half >= minimum(Output(changeAddress, half))) {
                tokenOutputs.add(0, Output(changeAddress, cleanAda - half))
                tokenOutputs.add(0, Output(changeAddress, half))
            } else if (cleanAda >= minimum(Output(changeAddress, cleanAda))) {
                tokenOutputs.add(0, Output(changeAddress, cleanAda))
            } else if (tokenOutputs.isNotEmpty()) {
                tokenOutputs[0] = tokenOutputs[0].copy(lovelace = tokenOutputs[0].lovelace + cleanAda)
            }
            return tokenOutputs
        }

        var fee = BigInteger.ZERO
        repeat(16) {
            val currentOutputs = outputsForFee?.invoke(fee) ?: outputs
            require(currentOutputs.all { it.address.isNotBlank() && it.lovelace.signum() >= 0 }) { "Invalid output" }
            val assets = sortedMapOf<Pair<String, String>, BigInteger>(compareBy({ it.first }, { it.second }))
            inputs.forEach { addAssets(assets, it.nativeAssets, 1) }
            currentOutputs.forEach { addAssets(assets, it.nativeAssets, -1) }
            require(assets.values.all { it.signum() >= 0 }) { "Insufficient native assets" }
            val changeAssets = assets.filterValues { it.signum() > 0 }.map { (key, amount) -> NativeAsset(key.second, key.first, amount) }
            val available = inputs.fold(withdrawals + refunds - deposits) { sum, input -> sum + input.lovelace } -
                currentOutputs.fold(BigInteger.ZERO) { sum, output -> sum + output.lovelace }
            require(available.signum() >= 0) { "Insufficient funds; reduce the requested amount or add funds" }
            currentOutputs.forEach { require(it.lovelace >= minimum(it)) { "Recipient output is below minimum ADA" } }
            var change = available - fee
            require(change.signum() >= 0) { "Insufficient funds for fee; reduce the requested amount or add funds" }
            var exactChange = false
            if (changeLayout == null && outputsForFee == null && (change.signum() > 0 || changeAssets.isNotEmpty())) {
                val minChange = minimum(Output(changeAddress, change, changeAssets))
                if (change < minChange) {
                    val retained = if (changeAssets.isEmpty()) BigInteger.ZERO else minChange
                    require(available >= retained) { "Insufficient change ADA; reduce the requested amount or add funds" }
                    fee = available - retained
                    change = retained
                    exactChange = true
                }
            }
            val finalOutputs = currentOutputs.toMutableList()
            if (changeLayout != null) {
                val planned = plannedChange(change, changeAssets)
                if (planned.isEmpty() && currentOutputs.isNotEmpty()) fee += change
                require(planned.isNotEmpty() || currentOutputs.isNotEmpty()) { "Insufficient ADA for a defragmentation output" }
                finalOutputs.addAll(planned)
            } else if (change.signum() != 0 || changeAssets.isNotEmpty()) {
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
            val requiredFee = amount(
                command(
                    "calculate-min-fee",
                    "--tx-body-file",
                    bodyPath,
                    "--protocol-params-file",
                    protocolPath,
                    "--witness-count",
                    witnessCount.toString(),
                    "--byron-witness-count",
                    "0",
                    "--reference-script-size",
                    referenceScriptSize.toString(),
                    "--output-text"
                )
            )
            if (exactChange) require(requiredFee == fee) { "Insufficient change ADA; reduce the requested amount or add funds" }
            if (requiredFee <= fee) {
                val text = connection.commandReadFile(bodyPath)
                val cbor = json.readTree(text).path("cborHex").asString()
                val decoded = SubmittedTransactionDecoder.decodeSubmitted(cbor, era)
                for (index in currentOutputs.size until finalOutputs.size) {
                    val output = finalOutputs[index]
                    require(output.lovelace >= minimum(output)) { "Insufficient change ADA; reduce the requested amount or add funds" }
                }
                val actualFee = decoded.body
                    .entries()
                    .single { it.first.integer() == BigInteger.TWO }
                    .second
                    .integer()
                require(actualFee == fee) { "Built transaction fee differs from balanced fee" }
                maxValueSize?.let { limit ->
                    decoded.batch.parent.outputs.forEach { output ->
                        val raw = output.raw
                        val value: CborSpan = if (raw.major == 4) raw.array()[1] else raw.entries().single { it.first.integer() == BigInteger.ONE }.second
                        require(BigInteger.valueOf((value.end - value.start).toLong()) <= limit) { "Transaction output exceeds maximum value size" }
                    }
                }
                val witnessArray = CborArray.create()
                repeat(witnessCount) {
                    witnessArray.add(
                        CborArray.create().apply {
                            add(CborByteString.create(ByteArray(32)))
                            add(CborByteString.create(ByteArray(64)))
                        }
                    )
                }
                val witnessSize = 2L + witnessArray.toCborByteArray().size + if (eraNumber >= QueryTip.ERA_CONWAY) 3 else 0
                val auxiliary = decoded.batch.parent.auxiliaryData
                val auxiliarySize = auxiliary?.let { it.end - it.start } ?: 1
                val envelopeSize = 1L + (decoded.body.end - decoded.body.start) + witnessSize + auxiliarySize +
                    if (eraNumber in QueryTip.ERA_ALONZO..QueryTip.ERA_CONWAY) 1 else 0
                require(BigInteger.valueOf(envelopeSize) <= maxTxSize) { "Transaction exceeds maximum transaction size" }
                val txId = command("txid", "--tx-body-file", bodyPath, "--output-text").trim().lowercase()
                require(txId == decoded.id) { "CLI transaction ID does not match transaction body" }
                return CliBody(text, txId, actualFee, decoded.batch.parent.outputs.size, Math.toIntExact(envelopeSize))
            }
            fee = fee.max(requiredFee)
        }
        throw IllegalArgumentException("Transaction fee did not converge after 16 iterations")
    }

    fun validateSignedEnvelopeSize(
        envelope: String,
        protocolParameters: String
    ) {
        val cbor = json.readTree(envelope).path("cborHex").asString()
        require(cbor.isNotEmpty() && cbor.length % 2 == 0 && cbor.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Invalid signed transaction"
        }
        val maxTxSize = json
            .readTree(protocolParameters)
            .path("maxTxSize")
            .asString()
            .toBigInteger()
        require(BigInteger.valueOf((cbor.length / 2).toLong()) <= maxTxSize) { "Signed transaction exceeds maximum transaction size" }
    }
}
