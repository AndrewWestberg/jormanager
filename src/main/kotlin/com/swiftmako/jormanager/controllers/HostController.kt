package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.repositories.HostRepository
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Sort
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller
import java.io.BufferedReader
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
    fun addHost(host: Host): SocketResponse<String> {
        // test the host connectivity
        val ssh = SSHClient()
        ssh.loadKnownHosts()
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connect(host.hostname, host.sshPort)
        try {
            ssh.authPublickey(host.sshUser, host.sshPemPath)
            ssh.startSession().use { session ->
                val cmd = session.exec("hostnamectl")
                val hostInfo = cmd.inputStream.bufferedReader().use(BufferedReader::readText)
                cmd.join()
                hostRepository.save(host)
                return SocketResponse.Success(type = "addhost", data = hostInfo)
            }
        } catch (e: IOException) {
            val error = "IOException communicating with ${host.hostname}"
            log.error(error, e)
            return SocketResponse.Error(type = "addhost", exception = e)
        } catch (e: Throwable) {
            val error = "Fatal error communicating with ${host.hostname}!"
            log.error(error, e)
            return SocketResponse.Error(type = "addhost", exception = e)
        } finally {
            ssh.disconnect()
        }
    }
}