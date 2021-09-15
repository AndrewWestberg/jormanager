package com.swiftmako.jormanager.controllers.utils

import com.swiftmako.jormanager.entities.Host
import kotlinx.io.pool.DefaultPool
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class SSHClientPool(private val host: Host) : DefaultPool<SSHClient>(4) {

    private val log by lazy {  LoggerFactory.getLogger(SSHClientPool::class.java) }

    override fun produceInstance(): SSHClient {
        return SSHClient().apply {
            loadKnownHosts()
            addHostKeyVerifier(PromiscuousVerifier())
            useCompression()
            connect(host.hostname, host.sshPort)
            authPublickey(host.sshUser, host.sshPemPath)
            connection.keepAlive.keepAliveInterval = 30
        }
    }

    override fun validateInstance(instance: SSHClient) {
        try {
            instance.startSession().use { session ->
                session.exec("pwd").use { cmd ->
                    cmd.join(5, TimeUnit.SECONDS)
                    if (cmd.exitStatus != 0) {
                        throw SSHRuntimeException("validateInstance failure: $host")
                    }
                }
            }
        } catch (e: Throwable) {
            disposeInstance(instance)
            throw e
        }
    }

    override fun disposeInstance(instance: SSHClient) {
        try {
            instance.close()
        } catch (t: Throwable) {
            log.error("Error closing SSHClient!", t)
        }
    }

    companion object {
        private val poolMap = mutableMapOf<Host, SSHClientPool>()
        fun getInstance(host: Host): SSHClientPool {
            return poolMap[host] ?: run {
                val newPool = SSHClientPool(host)
                poolMap[host] = newPool
                newPool
            }
        }

        fun shutdown() {
            poolMap.forEach { (_, sshClientPool) ->
                sshClientPool.dispose()
            }
            poolMap.clear()
        }
    }

}