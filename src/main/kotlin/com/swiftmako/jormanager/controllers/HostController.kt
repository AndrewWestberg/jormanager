package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.repositories.HostRepository
import net.schmizz.sshj.common.SSHRuntimeException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.io.IOException

@Controller
class HostController @Autowired constructor(
        private val hostRepository: HostRepository
) {

    private val log = LoggerFactory.getLogger(HostController::class.java)

    @MessageMapping("/hosts")
    @SendTo("/topic/messages")
    fun getHosts(): SocketResponse<List<Host>> {
        val hosts = hostRepository.findAll(Sort.by(Sort.Direction.ASC, "hostname"))
        return SocketResponse.Success(type = "hosts", data = hosts)
    }

    @MessageMapping("/addhost")
    @SendTo("/topic/messages")
    @Transactional
    fun addHost(host: Host): SocketResponse<String> {
        // test the host connectivity
        try {
            val hostConnection = HostConnection(host)
            // get the host information
            var hostInfo = hostConnection.command("lsb_release -idrc")
            // create the node home path directory if it doesn't exist
            hostConnection.command("mkdir -p ${host.nodeHomePath}")
            // make sure cardano-cli exists
            if (!hostConnection.commandFileExists(host.cardanoCliPath)) {
                throw SSHRuntimeException("File at '${host.cardanoCliPath}' does not exist!")
            }
            val cliVersion = hostConnection.command("${host.cardanoCliPath} --version")
            hostInfo += "       cardano-cli: $cliVersion"
            // make sure cardano-node exists
            if (!hostConnection.commandFileExists(host.cardanoNodePath)) {
                throw SSHRuntimeException("File at '${host.cardanoNodePath}' does not exist!")
            }
            val nodeVersion = hostConnection.command("${host.cardanoNodePath} --version")
            hostInfo += "      cardano-node: $nodeVersion"

            if (host.jcliPath?.isNotBlank() == true) {
                // make sure jcli exists
                if (!hostConnection.commandFileExists(host.jcliPath)) {
                    throw SSHRuntimeException("File at '${host.jcliPath}' does not exist!")
                }
                val jcliVersion = hostConnection.command("${host.jcliPath} --version")
                hostInfo += "              jcli: $jcliVersion"
            }
            host.id?.let { id ->
                hostRepository.findByIdOrNull(id)
            }?.let { repositoryHost ->
                hostRepository.save(
                        repositoryHost.copy(
                                type = host.type,
                                hostname = host.hostname,
                                sshUser = host.sshUser,
                                sshPort = host.sshPort,
                                sshPemPath = host.sshPemPath,
                                cardanoCliPath = host.cardanoCliPath,
                                cardanoNodePath = host.cardanoNodePath,
                                nodeHomePath = host.nodeHomePath,
                                jcliPath = host.jcliPath
                        )
                )
            } ?: hostRepository.save(host)
            return SocketResponse.Success(type = "addhost", data = hostInfo)
        } catch (e: IOException) {
            val error = "IOException communicating with ${host.hostname}"
            log.error(error, e)
            return SocketResponse.Error(type = "addhost", exception = e)
        } catch (e: Throwable) {
            val error = "Fatal error communicating with ${host.hostname}!"
            log.error(error, e)
            return SocketResponse.Error(type = "addhost", exception = e)
        }
    }
}