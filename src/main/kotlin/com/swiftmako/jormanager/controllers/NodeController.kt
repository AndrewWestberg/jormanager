package com.swiftmako.jormanager.controllers

import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.CreateNodeRequest
import com.swiftmako.jormanager.model.Genesis
import com.swiftmako.jormanager.model.ProtocolParameters
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import kotlinx.coroutines.channels.BroadcastChannel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.io.IOException

@Controller
class NodeController @Autowired constructor(
        private val nodeRepository: NodeRepository,
        private val hostRepository: HostRepository,
        private val fileRepository: FileRepository,
        private val walletRepository: WalletRepository,
        private val walletUtils: WalletUtils,
        private val webSocketTemplate: SimpMessagingTemplate,
        @Qualifier("nodesChannel") private val nodesChannel: BroadcastChannel<Node>,
        private val moshi: Moshi
) {
    private val log = LoggerFactory.getLogger(NodeController::class.java)

    @MessageMapping("/nodes")
    @SendTo("/topic/messages")
    fun getHosts(): SocketResponse<List<Node>> {
        val nodes = nodeRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))
        return SocketResponse.Success(type = "nodes", data = nodes)
    }

    @MessageMapping("/restartnode")
    @SendTo("/topic/messages")
    @Synchronized
    fun restartNode(nodeId: Long): SocketResponse<String> {
        return try {
            nodeRepository.findByIdOrNull(nodeId)?.let { node ->
                hostRepository.findByIdOrNull(node.hostId)?.let { host ->
                    HostConnection(host).use { hostConnection ->
                        val processId = if (hostConnection.hasSystemd) {
                            hostConnection.command("systemctl show --property MainPID --value ${node.name}-node.service").trim()
                        } else {
                            throw IllegalAccessError("This node does not support automatic restart.")
                        }
                        hostConnection.command("kill -INT $processId")
                        try {
                            Thread.sleep(1000)
                            hostConnection.command("kill -TERM $processId")
                            Thread.sleep(1000)
                            hostConnection.command("kill -KILL $processId")
                        } catch (t: Throwable) {
                            log.debug("kill command error!", t)
                        }
                        SocketResponse.Success(type = "restartnode", data = "triggered")
                    }
                } ?: throw IllegalArgumentException("Host not found!")
            } ?: throw IllegalArgumentException("Node not found!")
        } catch (e: Throwable) {
            log.error("Error Restarting Node!", e)
            SocketResponse.Error(type = "restartnode", exception = e)
        }
    }

    @MessageMapping("/createnode")
    @SendTo("/topic/messages")
    @Transactional
    fun createNode(request: CreateNodeRequest): SocketResponse<String> {
        try {
            hostRepository.findByIdOrNull(request.hostId)?.let { host ->

                HostConnection(host).use { hostConnection ->
                    // validate sudo password right away
                    hostConnection.sudoCommand("pwd", request.sudoPassword)

                    when (request.type) {
                        NODE_TYPE_RELAY -> {
                            val nodeFolder = "${host.nodeHomePath}${File.separator}${request.name}"
                            createNodeFolders(hostConnection, nodeFolder)
                            val genesisByronFile = createGenesisFile("byron", request.genesisByronFileId, hostConnection, nodeFolder)
                            createGenesisFile("shelley", request.genesisShelleyFileId, hostConnection, nodeFolder)
                            createTopologyFile(genesisByronFile.name, hostConnection, nodeFolder)
                            val (configFileId, ekgPort) = createConfigFile(request.hostId, genesisByronFile.name, hostConnection, nodeFolder)
                            createEnvFile(hostConnection, nodeFolder, request.listen, request.port)
                            createSystemdFile(request, host, hostConnection)
                            createManualStartupScripts(request, host, hostConnection)

                            if (request.isDefault) {
                                val oldDefault = nodeRepository.findDefault()
                                oldDefault?.let {
                                    // Make old default no longer the default
                                    nodeRepository.save(oldDefault.copy(isDefault = false))
                                }
                            }

                            val node = Node(
                                    hostId = host.id!!,
                                    color = request.color,
                                    type = request.type,
                                    processorThreads = request.processorThreads,
                                    name = request.name,
                                    listen = request.listen,
                                    port = request.port,
                                    ekgPort = ekgPort,
                                    genesisByronFileId = request.genesisByronFileId,
                                    genesisShelleyFileId = request.genesisShelleyFileId,
                                    configFileId = configFileId,
                                    isDefault = request.isDefault
                            )
                            val savedNode = nodeRepository.save(node)

                            // send it to the channel for monitoring
                            nodesChannel.offer(savedNode)

                            // send all to the client for ui updates
                            val nodes = nodeRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))
                            webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "nodes", data = nodes))
                        }
                        NODE_TYPE_CORE -> {
                            nodeRepository.findDefault()?.let { defaultNode ->
                                fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisFile ->
                                    val genesis = moshi.adapter(Genesis::class.java).fromJson(genesisFile.content)
                                    val magicString = if (genesis?.networkMagic == 42) {
                                        "--testnet-magic ${genesis.networkMagic}"
                                    } else {
                                        "--mainnet"
                                    }

                                    hostRepository.findByIdOrNull(defaultNode.hostId)?.let { defaultHost ->
                                        HostConnection(defaultHost, defaultNode).use { defaultHostConnection ->
                                            val protocolParamsJson = hostConnection.command("${host.cardanoCliPath} shelley query protocol-parameters --cardano-mode $magicString").trim()
                                            hostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParamsJson)
                                            val protocolParameters = moshi.adapter(ProtocolParameters::class.java).fromJson(protocolParamsJson)
                                                    ?: throw IOException("Invalid protocol params!")

                                            // 1. Create a transaction to dump EVERYTHING into
                                            var fee = 0L
                                            val transaction = StringBuilder()
                                            val certificates = StringBuilder()
                                            transaction.append("${host.cardanoCliPath} shelley transaction build-raw ")
                                            val feePayerAccount = walletRepository.findByIdOrNull(request.registrationFeesAccount)
                                                    ?: throw IOException("Registration fees account not found!")
                                            val utxos = walletUtils.getUtxos(host, hostConnection, feePayerAccount.paymentAddr)
                                            utxos.forEach { utxo ->
                                                transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                                            }

                                            // 2. Register owner address on the chain if not yet registered
                                            val ownerStakingAccount = walletRepository.findByIdOrNull(request.ownerStakingAccount)
                                                    ?: throw IOException("Owner staking account not found!")
                                            val ownerStakingWalletItem = walletUtils.getWalletItem(defaultHost, defaultHostConnection, ownerStakingAccount)
                                            if (!ownerStakingWalletItem.stakingAddrRegistered) {
                                                // owner staking address is *not* registered. We should register it on chain as part of the transaction
                                                ownerStakingAccount.stakingRegCert?.let { stakingRegCert ->
                                                    defaultHostConnection.commandWriteFile("/tmp/owner.staking.cert", stakingRegCert.content)
                                                } ?: throw IOException("Staking reg cert not found on owner account!")
                                                fee += protocolParameters.keyDeposit
                                                certificates.append("--certificate /tmp/owner.staking.cert ")
                                            }

                                            // 3. Register rewards address on the chain if not yet registered
                                            if (request.rewardsStakingAccount != request.ownerStakingAccount) {
                                                val rewardsStakingAccount = walletRepository.findByIdOrNull(request.rewardsStakingAccount)
                                                        ?: throw IOException("Rewards staking account not found!")
                                                val rewardsStakingWalletItem = walletUtils.getWalletItem(defaultHost, defaultHostConnection, rewardsStakingAccount)
                                                if (!rewardsStakingWalletItem.stakingAddrRegistered) {
                                                    // rewards staking address is *not* registered. We should register it on chain as part of the transaction
                                                    rewardsStakingAccount.stakingRegCert?.let { stakingRegCert ->
                                                        defaultHostConnection.commandWriteFile("/tmp/rewards.staking.cert", stakingRegCert.content)
                                                    }
                                                            ?: throw IOException("Staking reg cert not found on rewards account!")
                                                    fee += protocolParameters.keyDeposit
                                                    certificates.append("--certificate /tmp/rewards.staking.cert ")
                                                }
                                            }

                                            // 5. Keys
                                            var coreSKeyId = -1L
                                            var coreVKeyId = -1L
                                            var coreCounterId = -1L
                                            if (request.generateColdKeys) {
                                                defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley node key-gen --verification-key-file /tmp/core.node.vkey --signing-key-file /tmp/core.node.skey --operational-certificate-issue-counter /tmp/core.node.counter")
                                                val coreSKeyContent = File("/tmp/core.node.skey").inputStream().bufferedReader().use { it.readText() }
                                                val coreVKeyContent = File("/tmp/core.node.vkey").inputStream().bufferedReader().use { it.readText() }
                                                val coreCounterContent = File("/tmp/core.node.counter").inputStream().bufferedReader().use { it.readText() }
                                                val coreSKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.node.skey", content = coreSKeyContent)
                                                val coreVKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.node.vkey", content = coreVKeyContent)
                                                val coreCounter = com.swiftmako.jormanager.entities.File(name = "${request.name}.node.counter", content = coreCounterContent)
                                                coreSKeyId = fileRepository.save(coreSKey).id!!
                                                coreVKeyId = fileRepository.save(coreVKey).id!!
                                                coreCounterId = fileRepository.save(coreCounter).id!!
                                            } else {
                                                File("/tmp/core.node.skey").outputStream().bufferedWriter().use { it.write(requireNotNull(request.coldSKey)) }
                                                File("/tmp/core.node.vkey").outputStream().bufferedWriter().use { it.write(requireNotNull(request.coldVKey)) }
                                                File("/tmp/core.node.counter").outputStream().bufferedWriter().use { it.write(requireNotNull(request.coldCounter)) }
                                                val coreSKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.node.skey", content = requireNotNull(request.coldSKey))
                                                val coreVKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.node.vkey", content = requireNotNull(request.coldVKey))
                                                val coreCounter = com.swiftmako.jormanager.entities.File(name = "${request.name}.node.counter", content = requireNotNull(request.coldCounter))
                                                coreSKeyId = fileRepository.save(coreSKey).id!!
                                                coreVKeyId = fileRepository.save(coreVKey).id!!
                                                coreCounterId = fileRepository.save(coreCounter).id!!
                                            }
                                            var vrfSKeyId = -1L
                                            var vrfVKeyId = -1L
                                            if (request.generateVRFKeys) {
                                                defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley node key-gen-VRF --verification-key-file /tmp/core.vrf.vkey --signing-key-file /tmp/core.vrf.skey")
                                                val vrfSKeyContent = File("/tmp/core.vrf.skey").inputStream().bufferedReader().use { it.readText() }
                                                val vrfVKeyContent = File("/tmp/core.vrf.vkey").inputStream().bufferedReader().use { it.readText() }
                                                val vrfSKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.vrf.skey", content = vrfSKeyContent)
                                                val vrfVKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.vrf.vkey", content = vrfVKeyContent)
                                                vrfSKeyId = fileRepository.save(vrfSKey).id!!
                                                vrfVKeyId = fileRepository.save(vrfVKey).id!!
                                            } else {
                                                File("/tmp/core.vrf.skey").outputStream().bufferedWriter().use { it.write(requireNotNull(request.vrfSKey)) }
                                                File("/tmp/core.vrf.vkey").outputStream().bufferedWriter().use { it.write(requireNotNull(request.vrfVKey)) }
                                                val vrfSKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.vrf.skey", content = requireNotNull(request.vrfSKey))
                                                val vrfVKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.vrf.vkey", content = requireNotNull(request.vrfVKey))
                                                vrfSKeyId = fileRepository.save(vrfSKey).id!!
                                                vrfVKeyId = fileRepository.save(vrfVKey).id!!
                                            }
                                            var kesSKeyId = -1L
                                            var kesVKeyId = -1L
                                            if (request.generateKESKeys) {
                                                defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley node key-gen-KES --verification-key-file /tmp/core.kes.vkey --signing-key-file /tmp/core.kes.skey")
                                                val kesSKeyContent = File("/tmp/core.kes.skey").inputStream().bufferedReader().use { it.readText() }
                                                val kesVKeyContent = File("/tmp/core.kes.vkey").inputStream().bufferedReader().use { it.readText() }
                                                val kesSKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.kes.skey", content = kesSKeyContent)
                                                val kesVKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.kes.vkey", content = kesVKeyContent)
                                                kesSKeyId = fileRepository.save(kesSKey).id!!
                                                kesVKeyId = fileRepository.save(kesVKey).id!!
                                            } else {
                                                File("/tmp/core.kes.skey").outputStream().bufferedWriter().use { it.write(requireNotNull(request.kesSKey)) }
                                                File("/tmp/core.kes.vkey").outputStream().bufferedWriter().use { it.write(requireNotNull(request.kesVKey)) }
                                                val kesSKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.kes.skey", content = requireNotNull(request.kesSKey))
                                                val kesVKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.kes.vkey", content = requireNotNull(request.kesVKey))
                                                kesSKeyId = fileRepository.save(kesSKey).id!!
                                                kesVKeyId = fileRepository.save(kesVKey).id!!
                                            }

                                            val poolId = defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley stake-pool id --verification-key-file /tmp/core.node.vkey").trim()

                                            // 4. Create and upload metadata files
                                            val itnWitnessSign = if (request.metadata?.extended?.itn?.privateKey != null) {
                                                File("/tmp/core.pool.id").outputStream().bufferedWriter().use { it.write(poolId) }
                                                File("/tmp/core.itn.skey").outputStream().bufferedWriter().use { it.write(request.metadata.extended.itn.privateKey) }
                                                defaultHostConnection.command("${defaultHost.jcliPath} key sign --secret-key /tmp/core.itn.skey /tmp/core.pool.id").trim()
                                            } else {
                                                null
                                            }
                                            val itnWitnessOwner = request.metadata.extended.itn.publicKey
                                            ***


                                        }
                                    } ?: throw IOException("Host not found for default node!")
                                } ?: throw IOException("Genesis file for default node not found!")
                            } ?: throw IOException("No default node!")
                        }
                        else -> {
                            throw IOException("Invalid node type: ${request.type}")
                        }
                    }
                }

                return SocketResponse.Success("createnode", "${request.name} created successfully!")
            } ?: throw IOException("Invalid HostId: ${request.hostId}")
        } catch (e: Throwable) {
            log.error("Error Creating Node!", e)
            return SocketResponse.Error(type = "createnode", exception = e)
        }
    }

    private fun createManualStartupScripts(request: CreateNodeRequest, host: Host, hostConnection: HostConnection) {
        val startNodeContent = """
            |#!/bin/bash
            |cd ${host.nodeHomePath}/${request.name}
            |source ${host.nodeHomePath}/${request.name}/env
            |${host.cardanoNodePath} \
            |  +RTS -N${request.processorThreads} -RTS run \
            |  --topology ${'$'}{TOPOLOGY} \
            |  --database-path ${'$'}{DATABASE_PATH} \
            |  --socket-path ${'$'}{SOCKET_PATH} \
            |  --host-addr ${'$'}{HOST_ADDR} \
            |  --port ${'$'}{PORT} \
            |  --config ${'$'}{CONFIG}
        """.trimMargin()
        hostConnection.commandWriteFile("${host.nodeHomePath}/${request.name}/startNode.sh", startNodeContent)
        hostConnection.command("chmod 555 ${host.nodeHomePath}/${request.name}/startNode.sh")

        val stopNodeContent = """
            |#!/bin/bash
            |PID=`ps -Af | grep cardano-node | grep ${request.name}\/topology | awk '{ print ${'$'}2 }'`
            |kill -s INT ${'$'}PID
            |sleep 3
            |kill -s KILL ${'$'}PID
        """.trimMargin()
        hostConnection.commandWriteFile("${host.nodeHomePath}/${request.name}/stopNode.sh", stopNodeContent)
        hostConnection.command("chmod 555 ${host.nodeHomePath}/${request.name}/stopNode.sh")
    }

    private fun createSystemdFile(request: CreateNodeRequest, host: Host, hostConnection: HostConnection) {
        val systemdContent = """
            |[Unit]
            |Description=Cardano Haskell Node - ${request.name}
            |After=syslog.target
            |StartLimitIntervalSec=0
            |
            |[Service]
            |Type=simple
            |Restart=always
            |RestartSec=5
            |User=${host.sshUser}
            |LimitNOFILE=131072
            |WorkingDirectory=${host.nodeHomePath}/${request.name}
            |EnvironmentFile=${host.nodeHomePath}/${request.name}/env
            |ExecStart=${host.cardanoNodePath} \
            |  +RTS -N${request.processorThreads} -RTS run \
            |  --topology ${'$'}{TOPOLOGY} \
            |  --database-path ${'$'}{DATABASE_PATH} \
            |  --socket-path ${'$'}{SOCKET_PATH} \
            |  --host-addr ${'$'}{HOST_ADDR} \
            |  --port ${'$'}{PORT} \
            |  --config ${'$'}{CONFIG}
            |KillSignal=SIGINT
            |StandardOutput=syslog
            |StandardError=syslog
            |SyslogIdentifier=${request.name}-node
            |
            |[Install]
            |WantedBy=multi-user.target
        """.trimMargin()

        hostConnection.sudoCommandWriteFile("/etc/systemd/system/${request.name}-node.service", systemdContent, request.sudoPassword)

        if (hostConnection.hasSystemd) {
            hostConnection.sudoCommand("systemctl daemon-reload", request.sudoPassword)
            hostConnection.sudoCommand("systemctl stop ${request.name}-node.service", request.sudoPassword)
            hostConnection.sudoCommand("systemctl start ${request.name}-node.service", request.sudoPassword)
            hostConnection.sudoCommand("systemctl enable ${request.name}-node.service", request.sudoPassword)
        }
    }

    private fun createEnvFile(hostConnection: HostConnection, nodeFolder: String, listen: String, port: Int): String {
        hostConnection.commandWriteFile("${nodeFolder}/env",
                """
                |TOPOLOGY=${nodeFolder}/topology.json
                |DATABASE_PATH=${nodeFolder}/db
                |SOCKET_PATH=${nodeFolder}/db/socket
                |HOST_ADDR=${listen}
                |PORT=${port}
                |CONFIG=${nodeFolder}/config.json
                """.trimMargin()
        )
        return hostConnection.command("chmod 400 ${nodeFolder}/env")
    }

    private fun createConfigFile(hostId: Long, genesisByronFileName: String, hostConnection: HostConnection, nodeFolder: String): Pair<Long, Int> {
        val nodeCount = nodeRepository.countForHost(hostId)
        val ekgPort = 12788 + (2 * nodeCount)
        val prometheusPort = 12789 + (2 * nodeCount)
        val configFile = fileRepository.findByName(genesisByronFileName.substringBeforeLast("-byron") + "-config.json")
        val configFileContent = configFile?.content
                ?.replace(Regex(""""ByronGenesisFile": .*,"""), """"ByronGenesisFile": "byron-genesis.json",""")
                ?.replace(Regex(""""ShelleyGenesisFile": .*,"""), """"ShelleyGenesisFile": "shelley-genesis.json",""")
                ?.replace(Regex(""""GenesisFile": .*,"""), """"GenesisFile": "shelley-genesis.json",""")
                ?.replace(Regex(""""TraceBlockFetchDecisions":.*(true|false),"""), """"TraceBlockFetchDecisions": true,""")
                ?.replace(Regex(""".*"defaultScribes.*\[\n.*\[\n.*StdoutSK.*\n.*stdout.*\n.*\]\n.*\],"""),
                        """
                                                |  "defaultScribes": [
                                                |    [
                                                |      "FileSK",
                                                |      "logs/node.json"
                                                |    ]
                                                |  ],
                                                """.trimMargin())
                ?.replace(Regex(""""rpLogLimitBytes": .*,"""), """"rpLogLimitBytes": 20000000,""")
                ?.replace(Regex(""""scFormat.*,"""), """"scFormat": "ScJson",""")
                ?.replace(Regex(""""scKind.*,"""), """"scKind": "FileSK",""")
                ?.replace(Regex(""""scName.*,"""), """"scName": "logs/node.json",""")
                ?.replace("12788", "$ekgPort")
                ?.replace("12798", "$prometheusPort")
        configFileContent?.let {
            log.info("Creating ${nodeFolder}/config.json from db file ${configFile.name}")
            hostConnection.commandWriteFile("${nodeFolder}/config.json", it)
        } ?: throw IOException("Config file not found in db!")

        return Pair(configFile.id!!, ekgPort)
    }

    private fun createTopologyFile(byronGenesisFileName: String, hostConnection: HostConnection, nodeFolder: String) {
        val topologyFile = fileRepository.findByName(byronGenesisFileName.substringBeforeLast("-byron") + "-topology.json")
        topologyFile?.let {
            log.info("Creating ${nodeFolder}/topology.json from db file ${topologyFile.name}")
            hostConnection.commandWriteFile("${nodeFolder}/topology.json", topologyFile.content)
        } ?: throw IOException("Topology file not found in db!")
    }

    private fun createGenesisFile(prefix: String, genesisFileId: Long, hostConnection: HostConnection, nodeFolder: String): com.swiftmako.jormanager.entities.File {
        val genesisFile = fileRepository.findByIdOrNull(genesisFileId)
        genesisFile?.let {
            log.info("Creating ${nodeFolder}/$prefix-genesis.json from db file ${genesisFile.name}")
            hostConnection.commandWriteFile("${nodeFolder}/$prefix-genesis.json", genesisFile.content)
            return genesisFile
        } ?: throw IOException("Genesis file not found in db!")
    }

    private fun createNodeFolders(hostConnection: HostConnection, nodeFolder: String) {
        hostConnection.command("mkdir -p ${nodeFolder}/db")
        hostConnection.command("mkdir -p ${nodeFolder}/logs")
    }

    companion object {
        const val NODE_TYPE_RELAY = "relay"
        const val NODE_TYPE_CORE = "core"
    }
}