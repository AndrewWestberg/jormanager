package com.swiftmako.jormanager.controllers.utils

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborReader
import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.sumByBigInteger
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.ChainPoint
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.ProtocolParameters
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.RegCert
import com.swiftmako.jormanager.model.StakeAddressInfo
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.model.WalletItem
import com.swiftmako.jormanager.model.toNativeAssetMap
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.ChainIngestionCoordinator
import java.io.IOException
import java.math.BigInteger
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.codec.Hex
import org.springframework.security.crypto.codec.Utf8
import org.springframework.security.crypto.encrypt.AesGcmBytesEncryptor
import org.springframework.security.crypto.encrypt.TextEncryptor
import org.springframework.stereotype.Component

@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
class WalletUtils
    @Autowired
    constructor(
        private val hostRepository: HostRepository,
        private val nodeRepository: NodeRepository,
        private val walletRepository: WalletRepository,
        private val fileRepository: FileRepository,
        private val argon2PasswordEncoder: Argon2PasswordEncoder,
        @param:Value("\${jormanager.spendingpassword}") private val spendingPasswordHash: String,
        private val stakingInfoAdapter: JsonAdapter<List<StakeAddressInfo>>,
        private val regCertAdapter: JsonAdapter<RegCert>,
        private val genesisAdapter: JsonAdapter<GenesisShelley>,
        private val queryTipAdapter: JsonAdapter<QueryTip>,
        private val chainIngestionCoordinator: ChainIngestionCoordinator,
        private val ledgerDao: LedgerDao,
        private val cardanoRepository: CardanoRepository,
    ) {
        private val log by lazy { LoggerFactory.getLogger("WalletUtils") }

        suspend fun getWalletItems(
            magicString: String,
            era: String
        ): List<WalletItem> {
            val walletItems = mutableListOf<WalletItem>()
            nodeRepository.findDefault()?.let { defaultNode ->
                hostRepository.findByIdOrNull(defaultNode.hostId)?.let { host ->
                    val hostConnection = HostConnection(host, defaultNode)
                    val socketPath = "--socket-path ${host.nodeHomePath}/${defaultNode.name}/db/socket"
                    val walletEntries = walletRepository.findAllNotDeleted()
                    val total = walletEntries.size
                    walletEntries.forEachIndexed { index, walletEntry ->
                        walletItems.add(
                            getWalletItem(host, hostConnection, magicString, socketPath, era, walletEntry, index + 1, total)
                        )
                        delay(1L) // check to see if we're interrupted
                    }
                } ?: log.error("Host for default node not found!")
            } ?: log.warn("No default node set! Cannot check wallet for updates!")

            return walletItems
        }

        fun getWalletItem(
            host: Host,
            hostConnection: HostConnection,
            magicString: String,
            socketPath: String,
            era: String,
            walletEntry: WalletEntry,
            index: Int? = null,
            total: Int? = null,
        ): WalletItem {
            val utxos = getUtxos(host, hostConnection, magicString, socketPath, era, walletEntry.paymentAddr, index, total)

            // Query the target node, not the pending-transaction overlay.
            val start = System.currentTimeMillis()
            val stakingInfo =
                if (walletEntry.type == "stake" || walletEntry.type == "pledge") {
                    getStakeAddressInfo(host, hostConnection, magicString, socketPath, era, requireNotNull(walletEntry.stakingAddr))
                } else {
                    emptyList()
                }
            val stakingAddrLovelace = stakingInfo.takeIf { it.isNotEmpty() }?.sumByBigInteger { it.rewardAccountBalance }

            (System.currentTimeMillis() - start).takeIf { it > 1000L }?.let {
                log.warn("stakeAddressInfo: ${walletEntry.stakingAddr}, ${it}ms")
            }

            val stakingAddrRegistered = stakingAddrLovelace != null

            return WalletItem(
                walletEntry.id!!,
                walletEntry.name,
                walletEntry.type,
                walletEntry.paymentAddr,
                walletEntry.paymentSkey != null,
                utxos.size.toLong(),
                utxos.sumByBigInteger { it.lovelace },
                walletEntry.stakingAddr,
                stakingAddrRegistered,
                stakingAddrLovelace ?: BigInteger.ZERO,
                utxos.toNativeAssetMap(),
            )
        }

        fun getStakeAddressInfo(
            host: Host,
            hostConnection: HostConnection,
            magicString: String,
            socketPath: String,
            era: String,
            address: String,
        ): List<StakeAddressInfo> {
            val args = listOf(host.cardanoCliPath, era, "query", "stake-address-info", "--address", address) +
                magicString.split(" ") + socketPath.split(" ") + listOf("--output-json")
            return stakingInfoAdapter.fromJson(hostConnection.commandArgs(args))
                ?: throw IOException("Invalid stake address information")
        }

        data class AccountSnapshot(
            val networkKey: String,
            val nodeId: Long,
            val hostId: Long,
            val address: String,
            val point: ChainPoint,
            val registered: Boolean,
            val rewardAccountBalance: String?,
            val stakeRegistrationDeposit: String?,
        )

        /** Query only at the requested, completely indexed point; never infer rewards from history. */
        fun getAccountSnapshot(node: Node, address: String, expectedPoint: ChainPoint): AccountSnapshot {
            val networkKey = cardanoRepository.networkKey(node)
            val host = hostRepository.findByIdOrNull(node.hostId) ?: throw IOException("Node host unavailable")
            val genesisFile = fileRepository.findByIdOrNull(node.genesisShelleyFileId) ?: throw IOException("Genesis unavailable")
            val genesis = genesisAdapter.fromJson(genesisFile.content) ?: throw IOException("Invalid genesis")
            val magic = if (genesis.networkId.equals("testnet", true)) "--testnet-magic ${genesis.networkMagic}" else "--mainnet"
            val socket = "--socket-path ${host.nodeHomePath}/${node.name}/db/socket"
            val era = cardanoRepository.getEra(host, node, magic, socket)
            val connection = HostConnection(host, node)
            fun checkIndexedPoint() {
                val state = chainIngestionCoordinator.status(networkKey)
                check(state.status == "complete" && state.lastCompletePoint == expectedPoint && state.observedTip == expectedPoint &&
                    cardanoRepository.networkKey(node) == networkKey) { "Account state is not available at the requested indexed point" }
            }
            fun tip(): ChainPoint {
                val args = listOf(host.cardanoCliPath, era, "query", "tip") + magic.split(" ") + socket.split(" ") + "--output-json"
                val value = queryTipAdapter.fromJson(connection.commandArgs(args)) ?: throw IOException("Invalid node tip")
                return ChainPoint(value.slot.toString(), value.hash.lowercase())
            }
            checkIndexedPoint()
            check(tip() == expectedPoint) { "Node is not at the requested indexed point" }
            val info = getStakeAddressInfo(host, connection, magic, socket, era, address)
            require(info.size <= 1 && info.all { it.address == address }) { "Invalid account query response" }
            check(tip() == expectedPoint) { "Node point changed during account query" }
            checkIndexedPoint()
            val account = info.singleOrNull()
            return AccountSnapshot(networkKey, requireNotNull(node.id), requireNotNull(host.id), address, expectedPoint,
                account != null, account?.rewardAccountBalance?.toString(), account?.stakeAddressDeposit?.toString())
        }

        fun getUtxos(
            host: Host,
            hostConnection: HostConnection,
            magicString: String,
            socketPath: String,
            era: String,
            paymentAddr: String,
            index: Int? = null,
            total: Int? = null,
        ): List<Utxo> {
            val node = nodeRepository.findDefault() ?: throw IOException("No default node")
            require(node.hostId == host.id) { "Wallet host does not own the target node" }
            require(socketPath == "--socket-path ${host.nodeHomePath}/${node.name}/db/socket") { "Wallet target node changed" }
            return ledgerDao.queryUtxos(cardanoRepository.networkKey(node), paymentAddr)
        }

        fun queryReferenceScriptSize(
            host: Host,
            hostConnection: HostConnection,
            magicString: String,
            socketPath: String,
            era: String,
            utxos: Collection<Utxo>,
        ): Long {
            if (utxos.isEmpty()) {
                return 0L
            }

            val inputs = utxos.joinToString(" ") { "--tx-in ${it.hash}#${it.ix}" }
            val output =
                hostConnection
                    .command(
                        "${host.cardanoCliPath} $era query ref-script-size $inputs $magicString $socketPath --output-text"
                    ).trim()
            return REFERENCE_SCRIPT_SIZE_REGEX
                .matchEntire(output)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
                ?: throw IOException("Unable to determine reference script size: $output")
        }

        fun upgradeShelleyRegcertToConway(
            regCertFile: File,
            protocolParameters: ProtocolParameters
        ): File {
            log.info("Upgrading Shelley staking reg cert to Conway")
            val regCert = regCertAdapter.fromJson(regCertFile.content)!!
            val array =
                (
                    CborReader
                        .createFromByteArray(
                            regCert.cborHex.hexToByteArray()
                        ).readDataItem() as CborArray
                ).elementAt(1) as CborArray
            val regCertConwayJson =
                regCertAdapter.toJson(
                    RegCert(
                        type = "CertificateConway",
                        description = "Stake Address Registration Certificate",
                        cborHex =
                            CborArray
                                .create()
                                .apply {
                                    add(CborInteger.create(7))
                                    add(array)
                                    add(CborInteger.create(protocolParameters.stakeAddressDeposit))
                                }.toCborByteArray()
                                .toHexString()
                    )
                )

            return fileRepository.save(
                regCertFile.copy(
                    content = regCertConwayJson
                )
            )
        }

        fun getSKeyContent(
            skey: File,
            spendingPassword: String
        ): String =
            if (skey.content.isHex) {
                decryptSKeyContent(skey, spendingPassword)
            } else {
                val cipherText = encryptSKeyContent(skey.content, spendingPassword)
                fileRepository.save(skey.copy(content = cipherText))
                skey.content
            }

        fun isValidSpendingPassword(spendingPassword: String): Boolean = argon2PasswordEncoder.matches(spendingPassword, spendingPasswordHash)

        fun decryptSKeyContentForRepair(
            ciphertext: String,
            spendingPassword: String
        ): String? =
            try {
                currentTextEncryptor(spendingPassword).decrypt(ciphertext)
            } catch (e: Exception) {
                try {
                    legacyCompatibleTextEncryptor(spendingPassword).decrypt(ciphertext)
                } catch (e: Exception) {
                    null
                }
            }

        fun encryptSKeyContentForRepair(
            cleartext: String,
            spendingPassword: String
        ): String {
            val textEncryptor = currentTextEncryptor(spendingPassword)
            return textEncryptor.encrypt(cleartext)
        }

        fun encryptSKeyContent(
            cleartext: String,
            spendingPassword: String
        ): String {
            if (!isValidSpendingPassword(spendingPassword)) {
                throw IllegalArgumentException("Invalid spending password!")
            }
            val textEncryptor = currentTextEncryptor(spendingPassword)
            return textEncryptor.encrypt(cleartext)
        }

        private fun decryptSKeyContent(
            skey: File,
            spendingPassword: String
        ): String {
            if (!isValidSpendingPassword(spendingPassword)) {
                throw IllegalArgumentException("Invalid spending password!")
            }

            val textEncryptor = currentTextEncryptor(spendingPassword)
            return try {
                textEncryptor.decrypt(skey.content)
            } catch (e: Exception) {
                val cleartext = legacyCompatibleTextEncryptor(spendingPassword).decrypt(skey.content)
                fileRepository.save(skey.copy(content = textEncryptor.encrypt(cleartext)))
                cleartext
            }
        }

        private fun currentTextEncryptor(password: String): TextEncryptor =
            textEncryptor(AesGcmBytesEncryptor.withPassword(password, S).build())

        private fun legacyCompatibleTextEncryptor(password: String): TextEncryptor {
            // Matches Encryptors.delux so existing keys can be decrypted and re-encrypted.
            val keySpec = PBEKeySpec(password.toCharArray(), Hex.decode(S), 1024, 256)
            val secretKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(keySpec)
            return textEncryptor(AesGcmBytesEncryptor.withSecretKey(secretKey).build())
        }

        private fun textEncryptor(encryptor: AesGcmBytesEncryptor): TextEncryptor =
            object : TextEncryptor {
                override fun encrypt(text: String): String = String(Hex.encode(encryptor.encrypt(Utf8.encode(text))))

                override fun decrypt(encryptedText: String): String = Utf8.decode(encryptor.decrypt(Hex.decode(encryptedText)))
            }

        companion object {
            const val S = "4b38652a506b513742655764375270794e3273473961596266670a"
            private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")
            private val REFERENCE_SCRIPT_SIZE_REGEX = Regex("Reference inputs scripts size is ([0-9]+) bytes\\.")
            val String.isHex: Boolean
                get() = this.matches(HEX_REGEX)
        }
    }
