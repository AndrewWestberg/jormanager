package com.swiftmako.jormanager.controllers.utils

import com.swiftmako.jormanager.entities.Host
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit

class HostConnection(private val host: Host) : Closeable {

    private val isRemote = host.type == "remote"
    private val sshDelegate = lazy {
        SSHClient().apply {
            loadKnownHosts()
            addHostKeyVerifier(PromiscuousVerifier())
            connect(host.hostname, host.sshPort)
            authPublickey(host.sshUser, host.sshPemPath)
        }
    }
    private val ssh by sshDelegate

    fun commandFileExists(filePath: String): Boolean {
        return if (isRemote) {
            command("if test -f $filePath; then echo true; fi").trim().toBoolean()
        } else {
            File(filePath).exists()
        }
    }

    fun command(command: String): String {
        return command(command.split(" "))
    }

    fun command(command: List<String>): String {
        return if (isRemote) {
            remoteCommand(command)
        } else {
            localCommand(command)
        }
    }

    private fun remoteCommand(c: List<String>): String {
        lateinit var cmd: Session.Command
        lateinit var output: String
        lateinit var errorOutput: String
        val command = c.joinToString(" ").trim()
        try {
            ssh.startSession().use { session ->
                session.exec(command).use { cmd ->
                    output = cmd.inputStream.bufferedReader().use(BufferedReader::readText)
                    errorOutput = cmd.errorStream.bufferedReader().use(BufferedReader::readText)
                    cmd.join(5, TimeUnit.SECONDS)
                    if (cmd.exitStatus != 0) {
                        throw SSHRuntimeException("Command '$command' exited with code ${cmd.exitStatus}: ${cmd.exitErrorMessage}, $errorOutput")
                    }
                }
            }
        } catch (e: Throwable) {
            throw SSHRuntimeException("Command '$command' exited with code ${cmd.exitStatus}: ${cmd.exitErrorMessage}, $errorOutput", e)
        }
        return output
    }

    private fun localCommand(c: List<String>): String {
        lateinit var process: Process
        lateinit var output: String
        lateinit var errorOutput: String
        val command = c.joinToString(" ").trim()
        try {
            process = ProcessBuilder(c).start()
            output = process.inputStream.bufferedReader().use(BufferedReader::readText)
            errorOutput = process.errorStream.bufferedReader().use(BufferedReader::readText)
            process.waitFor(5, TimeUnit.SECONDS)
            if (process.exitValue() != 0) {
                throw RuntimeException("Command '$command' exited with code ${process.exitValue()}: $errorOutput")
            }
        } catch (e: Throwable) {
            throw RuntimeException("Command '$command' exited with code ${process.exitValue()}: $errorOutput")
        }
        return output
    }

    override fun close() {
        if (sshDelegate.isInitialized()) {
            ssh.close()
        }
    }

}