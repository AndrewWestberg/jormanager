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
    fun createNode(request: CreateNodeRequest): SocketResponse<Boolean> {
        try {
            log.info("CreateNodeRequest: $request")
            hostRepository.findByIdOrNull(request.hostId)?.let { host ->
                HostConnection(host).use { hostConnection ->
                    when (request.type) {
                        NODE_TYPE_RELAY -> {
                            // Create Node folder
                            hostConnection.command("mkdir -p ${host.nodeHomePath}${File.separator}${request.name}")
                            // Create genesis file
                            val genesisFile = fileRepository.findByIdOrNull(request.genesisFileId)
                            genesisFile?.let {
                                hostConnection.commandWriteFile("${host.nodeHomePath}${File.separator}${request.name}${File.separator}genesis.json", genesisFile.content)
                            } ?: throw IOException("Genesis file not found in db!")
                            // Create the topology file
                            val topologyFile = fileRepository.findByName(genesisFile.name.substringBefore('-') + "-topology.json")
                            topologyFile?.let {
                                hostConnection.commandWriteFile("${host.nodeHomePath}${File.separator}${request.name}${File.separator}topology.json", topologyFile.content)
                            } ?: throw IOException("Topology file not found in db!")
                            val nodeCount = nodeRepository.countForHost(request.hostId)
                            val ekgPort = 12788 + (2 * nodeCount)
                            val prometheusPort = 12789 + (2 * nodeCount)
                            val configFile = fileRepository.findByName(genesisFile.name.substringBefore('-') + "-config.json")
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
                                            """.trimMargin("|"))
                                    ?.replace(Regex(""""rpLogLimitBytes": .*,"""), """"rpLogLimitBytes": 20000000,""")
                                    ?.replace(Regex(""""scFormat.*,"""), """"scFormat": "ScJson",""")
                                    ?.replace(Regex(""""scKind.*,"""), """"scKind": "FileSK",""")
                                    ?.replace(Regex(""""scName.*,"""), """"scName": "logs/node.json",""")
                                    ?.replace("12788", "$ekgPort")
                                    ?.replace("12798", "$prometheusPort")
                            configFileContent?.let {
                                hostConnection.commandWriteFile("${host.nodeHomePath}${File.separator}${request.name}${File.separator}config.json", it)
                            } ?: throw IOException("Config file not found in db!")

                        }
                        NODE_TYPE_CORE -> {
                            TODO("Not yet implemented")
                        }
                        else -> {
                            throw IOException("Invalid node type: ${request.type}")
                        }
                    }
                }

                return SocketResponse.Success("createnode", true)
            } ?: throw IOException("Invalid HostId: ${request.hostId}")
        } catch (e: Throwable) {
            log.error("Error Creating Node!", e)
            return SocketResponse.Error(type = "createnode", exception = e)
        }
    }

    companion object {
        const val NODE_TYPE_RELAY = "relay"
        const val NODE_TYPE_CORE = "core"
    }
}