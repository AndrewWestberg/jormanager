package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.CreateNodeRequest
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.io.IOException

@Controller
class NodeController @Autowired constructor(
        private val nodeRepository: NodeRepository,
        private val hostRepository: HostRepository,
        private val fileRepository: FileRepository
) {
    private val log = LoggerFactory.getLogger(NodeController::class.java)

    @MessageMapping("/createnode")
    @SendTo("/topic/messages")
    @Transactional
    fun createNode(request: CreateNodeRequest): SocketResponse<String> {
        try {
            hostRepository.findByIdOrNull(request.hostId)?.let { host ->
                HostConnection(host).use { hostConnection ->
                    when (request.type) {
                        NODE_TYPE_RELAY -> {
                            val nodeFolder = "${host.nodeHomePath}${File.separator}${request.name}"
                            createNodeFolders(hostConnection, nodeFolder)
                            val genesisFile = createGenesisFile(request.genesisFileId, hostConnection, nodeFolder)
                            createTopologyFile(genesisFile.name, hostConnection, nodeFolder)
                            createConfigFile(request.hostId, genesisFile.name, hostConnection, nodeFolder)
                            createEnvFile(hostConnection, nodeFolder, request.listen, request.port)

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
                                |  +RTS -N8 -RTS run \
                                |  --topology ${'$'}{TOPOLOGY} \
                                |  --database-path ${'$'}{DATABASE_PATH} \
                                |  --socket-path ${'$'}{SOCKET_PATH} \
                                |  --host-addr ${'$'}{HOST_ADDR} \
                                |  --port ${'$'}{PORT} \
                                |  --config ${'$'}{CONFIG}
                                |KillSignal="SIGINT"
                                |RestartKillSignal="SIGINT"
                                |StandardOutput=syslog
                                |StandardError=syslog
                                |SyslogIdentifier=${request.name}-node
                                |
                                |[Install]
                                |WantedBy=multi-user.target
                            """.trimMargin()

                            hostConnection.sudoCommandWriteFile("/etc/systemd/system/${request.name}-node.service", systemdContent, request.sudoPassword)

                            hostConnection.sudoCommand("systemctl daemon-reload", request.sudoPassword)
                            hostConnection.sudoCommand("systemctl start ${request.name}-node.service", request.sudoPassword)
                        }
                        NODE_TYPE_CORE -> {
                            TODO("Not yet implemented")
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

    private fun createConfigFile(hostId: Long, genesisFileName: String, hostConnection: HostConnection, nodeFolder: String) {
        val nodeCount = nodeRepository.countForHost(hostId)
        val ekgPort = 12788 + (2 * nodeCount)
        val prometheusPort = 12789 + (2 * nodeCount)
        val configFile = fileRepository.findByName(genesisFileName.substringBeforeLast('-') + "-config.json")
        val configFileContent = configFile?.content
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
            hostConnection.commandWriteFile("${nodeFolder}/config.json", it)
        } ?: throw IOException("Config file not found in db!")
    }

    private fun createTopologyFile(genesisFileName: String, hostConnection: HostConnection, nodeFolder: String) {
        val topologyFile = fileRepository.findByName(genesisFileName.substringBeforeLast('-') + "-topology.json")
        topologyFile?.let {
            hostConnection.commandWriteFile("${nodeFolder}/topology.json", topologyFile.content)
        } ?: throw IOException("Topology file not found in db!")
    }

    private fun createGenesisFile(genesisFileId: Long, hostConnection: HostConnection, nodeFolder: String): com.swiftmako.jormanager.entities.File {
        val genesisFile = fileRepository.findByIdOrNull(genesisFileId)
        genesisFile?.let {
            hostConnection.commandWriteFile("${nodeFolder}/genesis.json", genesisFile.content)
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