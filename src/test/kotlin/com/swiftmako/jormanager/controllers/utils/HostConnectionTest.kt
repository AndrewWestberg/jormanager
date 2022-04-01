package com.swiftmako.jormanager.controllers.utils

import com.muquit.libsodiumjna.SodiumLibrary
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.sumByBigInteger
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.ProtocolParameters
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.toNativeAssetMap
import com.swiftmako.jormanager.moshi.adapters.BigIntegerAdapter
import com.swiftmako.jormanager.moshi.adapters.JodaDateTimeAdapter
import com.swiftmako.jormanager.moshi.adapters.QueryUtxoJsonAdapter
import io.mockk.mockk
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.math.BigInteger
import kotlin.experimental.and

class HostConnectionTest {

    private val log by lazy {  LoggerFactory.getLogger("HostConnectionTest") }

    @Test
    fun testMultilineStringToFile() {
        val systemdContent = """
                                |[Unit]
                                |Description=Cardano Haskell Node - tickr
                                |After=syslog.target
                                |StartLimitIntervalSec=0
                                |
                                |[Service]
                                |Type=simple
                                |Restart=always
                                |RestartSec=5
                                |User=westbam
                                |LimitNOFILE=131072
                                |WorkingDirectory=/home/westbam/haskell/tickr
                                |EnvironmentFile=/home/westbam/haskell/tickr/env
                                |ExecStart=/home/westbam/.local/bin/cardano-node \\
                                |  +RTS -N8 -RTS run \\
                                |  --topology ${'$'}{TOPOLOGY} \\
                                |  --database-path ${'$'}{DATABASE_PATH} \\
                                |  --socket-path ${'$'}{SOCKET_PATH} \\
                                |  --host-addr ${'$'}{HOST_ADDR} \\
                                |  --port ${'$'}{PORT} \\
                                |  --config ${'$'}{CONFIG}
                                |KillSignal="SIGINT"
                                |RestartKillSignal="SIGINT"
                                |StandardOutput=syslog
                                |StandardError=syslog
                                |SyslogIdentifier=tickr-node
                                |
                                |[Install]
                                |WantedBy=multi-user.target
                            """.trimMargin()

        val hostConnection = HostConnection(Host(0, "local", "", "", "", "", 22, "", "", ""))
        hostConnection.sudoCommandWriteFile("/home/westbam/haskell/tickr-node.service", systemdContent, "******************")
    }

    @Test
    fun testQueryAddress() {
        val host = Host(0, "local", "/home/westbam/.local/bin/cardano-cli", "", "", "", 22, "", "/home/westbam/haskell", "")
        val defaultNode = Node(0, 0, null, "", "relay", 8, "local", "127.0.0.1", 22, 12788, 0, 0, 0,0, isDefault = true, configFileId = 0)
        val hostConnection = HostConnection(host, defaultNode)
        val output = hostConnection.command("${host.cardanoCliPath} query utxo --address 60f9a5546c4d82ee112781dd02074a8b4a68e33ecced8a27e24bd2642b --testnet-magic 42")
        println(output)
    }

    @Test
    fun testIsPortUsed() {
        val host = Host(0, "local", "/home/westbam/.local/bin/cardano-cli", "", "", "", 22, "", "/home/westbam/haskell", "")
        val defaultNode = Node(0, 0, null, "", "relay", 8, "local", "127.0.0.1", 22, 12788, 0, 0, 0,0, isDefault = true, configFileId = 0)
        val hostConnection = HostConnection(host, defaultNode)
        val port = 12955
        val output = hostConnection.command("ss -tulw").trim().contains(":$port")
        println(output)
    }

    @Test
    fun testSpamGuildStakeAddresses() {
        val libraryPath = "/usr/local/lib/libsodium.so"
        SodiumLibrary.setLibraryPath(libraryPath)

        val isRegistration = false

        val walletUtils = WalletUtils(mockk(), mockk(), mockk(), mockk(), mockk(), "", mockk(), QueryUtxoJsonAdapter(), mockk())

        val host = Host(0, "local", "/home/westbam/.local/bin/cardano-cli", "", "", "", 22, "", "/home/westbam/haskell", "")
        val defaultNode = Node(0, 0, null, "", "relay", 8, "guild", "127.0.0.1", 22, 12788, 0, 0, 0,0, isDefault = true, configFileId = 0)
        val hostConnection = HostConnection(host, defaultNode)

        val magicString = "--testnet-magic 141"
        val moshi = Moshi.Builder().add(BigIntegerAdapter).add(JodaDateTimeAdapter()).build()
        val queryTipAdapter = moshi.adapter(QueryTip::class.java)
        val protocolParamsAdapter = moshi.adapter(ProtocolParameters::class.java)
        val protocolParamsJson =
                hostConnection.command("${host.cardanoCliPath} query protocol-parameters $magicString")
                        .trim()
        hostConnection.commandWriteFile("/tmp/protocol-parameters-spam.json", protocolParamsJson)
        val protocolParameters = protocolParamsAdapter.fromJson(protocolParamsJson)
                ?: throw IOException("Invalid protocol params!")


        val blake2b256 = Blake2bDigest(256)

//        for (x in 23903..29999) {
        for (x in 0..1873) {

            while (true) {
                try {
                    log.debug("Waiting for next block...")
                    val queryTip = hostConnection.command("${host.cardanoCliPath} query tip $magicString").trim()
                    val block = queryTipAdapter.fromJson(queryTip)?.block
                            ?: throw IOException("Couldn't parse query tip!")
                    while (true) {
                        Thread.sleep(1000)
                        val qt = hostConnection.command("${host.cardanoCliPath} query tip $magicString").trim()
                        val nextBlock = queryTipAdapter.fromJson(qt)?.block
                                ?: throw IOException("Couldn't parse query tip!")
                        if (nextBlock > block) {
                            log.debug("...$nextBlock")
                            Thread.sleep(5000)
                            break
                        }
                    }

                    val start = x * 100L
                    val end = start + 99L

                    if(isRegistration) {
                        log.debug("Registering keys $start..$end...")
                    } else {
                        log.debug("Un-registering keys $start..$end...")
                    }

                    for (i in start..end) {
                        val bytes = longToBytes(i)
                        blake2b256.reset()
                        blake2b256.update(bytes, 0, bytes.size)
                        val output = ByteArray(32)
                        blake2b256.doFinal(output, 0)

                        // generate payment keys
                        val paymentSKey = """
                |{
                | "type": "PaymentSigningKeyShelley_ed25519",
                | "description": "Payment Signing Key",
                | "cborHex": "5820${output.toHexString()}"
                | }
            """.trimMargin()
                        File("/tmp/payment$i.skey").writeText(paymentSKey)
                        hostConnection.command("${host.cardanoCliPath} key verification-key --signing-key-file /tmp/payment$i.skey --verification-key-file /tmp/payment$i.vkey")

                        // generate staking keys
                        val stakingSKey = """
                |{
                | "type": "StakeSigningKeyShelley_ed25519",
                | "description": "Stake Signing Key",
                | "cborHex": "5820${output.toHexString()}"
                | }
            """.trimMargin()
                        File("/tmp/staking$i.skey").writeText(stakingSKey)
                        hostConnection.command("${host.cardanoCliPath} key verification-key --signing-key-file /tmp/staking$i.skey --verification-key-file /tmp/staking$i.vkey")

                        // generate payment address
                        val paymentAddress = hostConnection.command("${host.cardanoCliPath} address build --payment-verification-key-file /tmp/payment$i.vkey --staking-verification-key-file /tmp/staking$i.vkey $magicString").trim()
                        File("/tmp/payment$i.addr").writeText(paymentAddress)

                        //generate staking address
                        val stakingAddress = hostConnection.command("${host.cardanoCliPath} stake-address build --staking-verification-key-file /tmp/staking$i.vkey $magicString").trim()
                        File("/tmp/staking$i.addr").writeText(stakingAddress)

                        // generate stake reg-cert
                        hostConnection.command("${host.cardanoCliPath} stake-address registration-certificate --staking-verification-key-file /tmp/staking$i.vkey --out-file /tmp/staking$i.cert")

                        // generate stake dereg-cert
                        hostConnection.command("${host.cardanoCliPath} stake-address deregistration-certificate --stake-verification-key-file /tmp/staking$i.vkey --out-file /tmp/staking$i.dereg-cert")

                        // generate delegation cert
                        hostConnection.command("${host.cardanoCliPath} stake-address delegation-certificate --stake-verification-key-file /tmp/staking$i.vkey --cold-verification-key-file /home/westbam/haskell/gpool.node.vkey --out-file /tmp/staking$i.deleg.cert")
                    }

                    // 1. Create a transaction to dump EVERYTHING into
                    var depositAndFees = BigInteger.ZERO
                    var witnessCount = 0
                    val transaction = StringBuilder()
                    val certificates = StringBuilder()
                    val signingKeys = StringBuilder()
                    transaction.append("${host.cardanoCliPath} transaction build-raw ")
                    val feePayerAddress = File("/home/westbam/haskell/gfunds.payment.addr").readText().trim()
                    val utxos = walletUtils.getUtxos(
                            host,
                            hostConnection,
                            magicString,
                            feePayerAddress
                    )
                    utxos.forEach { utxo ->
                        transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                    }
                    log.debug("feePayerAccount balance: ${utxos.sumByBigInteger { it.lovelace }}")
                    witnessCount++ // fee payer is a witness
                    signingKeys.append("--signing-key-file /home/westbam/haskell/gfunds.payment.skey ")

                    // initial dummy value to return change to the fee payer account.
                    // We'll replace this with the actual change to return later
                    transaction.append("--tx-out $feePayerAddress+1234567890 ")

                    val queryTipString =
                            hostConnection.command("${host.cardanoCliPath} query tip $magicString")
                                    .trim()
                    val ttl = queryTipAdapter.fromJson(queryTipString)?.let { it.slot + 21600 }
                            ?: -1
                    transaction.append("--invalid-hereafter $ttl ")
                    transaction.append("--fee 100 ")

                    for (i in start..end) {
                        if (isRegistration) {
                            // 2. Register staking addresses on the chain
//                            depositAndFees += protocolParameters.stakeAddressDeposit
//                            certificates.append("--certificate /tmp/staking$i.cert ")

                            certificates.append("--certificate /tmp/staking$i.deleg.cert ")
                        } else {
                            // staking address is registered. We should *de* register it on chain as part of the transaction
                            certificates.append("--certificate /tmp/staking$i.dereg-cert ")
                        }
                        witnessCount++ // the staking.skey is a witness
                        signingKeys.append("--signing-key-file /tmp/staking$i.skey ")
                    }

                    // 8. Calculate fees
                    transaction.append(certificates)
                    transaction.append("--out-file /tmp/transaction-spam.txbody")
                    hostConnection.command(transaction.toString())

                    log.debug("depositAndFees: $depositAndFees")
                    val feesString = hostConnection.command("${host.cardanoCliPath} transaction calculate-min-fee --tx-body-file /tmp/transaction-spam.txbody --protocol-params-file /tmp/protocol-parameters-spam.json --tx-in-count ${utxos.size} --tx-out-count 1 $magicString --witness-count $witnessCount --byron-witness-count 0").trim()
                    val fees = feesString.split(" ")[0].toBigInteger()
                    log.debug("fees: $fees")
                    depositAndFees += fees
                    log.debug("final depositAndFees: $depositAndFees")

                    // 9. Create the transaction
                    val change = utxos.sumByBigInteger { it.lovelace } - depositAndFees + if (!isRegistration) {
                        // for a deregistration, we get the 2 ada deposit back as change
                        protocolParameters.stakeAddressDeposit * (witnessCount - 1).toBigInteger()
                    } else {
                        BigInteger.ZERO
                    }
                    if (change < BigInteger.ONE) {
                        throw IOException("Not enough funds to pay depositAndFees of $depositAndFees lovelace!")
                    }

                    val tokenChange = StringBuilder()
                    utxos.toNativeAssetMap().forEach { (currency, amount) ->
                        if (amount > BigInteger.ZERO) {
                            tokenChange.append("+$amount $currency")
                        }
                    }

                    val realTransaction = transaction.toString()
                            .replace("--fee 100 ", "--fee $fees ")
                            .replace(
                                    "--tx-out ${feePayerAddress}+1234567890 ",
                                    "--tx-out '${feePayerAddress}+$change$tokenChange' "
                            )
                    log.debug("StakeKey Transaction Command: $realTransaction")
                    hostConnection.command(realTransaction)

                    // 10. Sign the transaction
                    hostConnection.command("${host.cardanoCliPath} transaction sign --tx-body-file /tmp/transaction-spam.txbody $signingKeys $magicString --out-file /tmp/transaction-spam.txsigned")

                    // 11. Submit the transaction
                    hostConnection.command("${host.cardanoCliPath} transaction submit --tx-file /tmp/transaction-spam.txsigned $magicString")
                    val txid =
                            hostConnection.command("${host.cardanoCliPath} transaction txid --tx-body-file /tmp/transaction-spam.txbody")

                    println("Transaction ID: $txid")
                    break
                } catch (e: Throwable) {
                    log.error("error occurred!", e)
                }
            }
        }
    }

    private fun longToBytes(l: Long): ByteArray {
        var lng = l
        val result = ByteArray(Long.SIZE_BYTES)
        for (i in Long.SIZE_BYTES - 1 downTo 0) {
            result[i] = (lng and 0xFF).toByte()
            lng = lng shr java.lang.Byte.SIZE
        }
        return result
    }

    private fun bytesToLong(b: ByteArray): Long {
        var result: Long = 0
        for (i in 0 until Long.SIZE_BYTES) {
            result = result shl Byte.SIZE_BYTES
            result = result or (b[i] and 0xFF.toByte()).toLong()
        }
        return result
    }
}