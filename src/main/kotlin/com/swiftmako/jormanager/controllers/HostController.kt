package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.repositories.HostRepository
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit

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
    fun addHost(host: Host): SocketResponse<String> {
        // test the host connectivity
        SSHClient().use { ssh ->
            try {
                ssh.loadKnownHosts()
                ssh.addHostKeyVerifier(PromiscuousVerifier())
                ssh.connect(host.hostname, host.sshPort)
                ssh.authPublickey(host.sshUser, host.sshPemPath)
                // get the host information
                var hostInfo = ssh.command("hostnamectl")
                // create the node home path directory if it doesn't exist
                ssh.command("mkdir -p ${host.nodeHomePath}")
                // make sure cardano-cli exists
                if (!ssh.commandFileExists(host.cardanoCliPath)) {
                    throw SSHRuntimeException("File at '${host.cardanoCliPath}' does not exist!")
                }
                val cliVersion = ssh.command("${host.cardanoCliPath} --version")
                hostInfo += "       cardano-cli: $cliVersion"
                // make sure cardano-node exists
                if (!ssh.commandFileExists(host.cardanoNodePath)) {
                    throw SSHRuntimeException("File at '${host.cardanoNodePath}' does not exist!")
                }
                val nodeVersion = ssh.command("${host.cardanoNodePath} --version")
                hostInfo += "      cardano-node: $nodeVersion"
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
                                    nodeHomePath = host.nodeHomePath
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

    fun SSHClient.command(command: String): String {
        lateinit var cmd: Session.Command
        lateinit var output: String
        lateinit var errorOutput: String
        try {
            startSession().use { session ->
                cmd = session.exec(command)
                output = cmd.inputStream.bufferedReader().use(BufferedReader::readText)
                errorOutput = cmd.errorStream.bufferedReader().use(BufferedReader::readText)
                cmd.join(5, TimeUnit.SECONDS)
            }
            if (cmd.exitStatus != 0) {
                throw SSHRuntimeException("Command '$command' exited with code ${cmd.exitStatus}: ${cmd.exitErrorMessage}, $errorOutput")
            }
        } catch (e: Throwable) {
            throw SSHRuntimeException("Command '$command' exited with code ${cmd.exitStatus}: ${cmd.exitErrorMessage}, $errorOutput", e)
        }
        return output
    }

    fun SSHClient.commandFileExists(filePath: String): Boolean {
        return command("if test -f $filePath; then echo true; fi").trim().toBoolean()
    }
}