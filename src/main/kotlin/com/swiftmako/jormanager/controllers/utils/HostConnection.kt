package com.swiftmako.jormanager.controllers.utils

import com.swiftmako.jormanager.entities.Host
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.net.DatagramSocket
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.random.Random


class HostConnection(private val host: Host) : Closeable {

    private val log = LoggerFactory.getLogger("HostConnection")

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
        val commandList = mutableListOf<String>()
        val m: Matcher = Pattern.compile("([^']\\S*|'.+?')\\s*").matcher(command)
        while (m.find()) {
            commandList.add(m.group(1))
        }
        return command(commandList)
    }

    fun command(command: List<String>): String {
        return if (isRemote) {
            remoteCommand(command)
        } else {
            localCommand(command)
        }
    }

    fun sudoCommand(command: String, sudoPassword: String?): String {
        val commandList = mutableListOf<String>()
        val m: Matcher = Pattern.compile("([^']\\S*|'.+?')\\s*").matcher(command)
        while (m.find()) {
            commandList.add(m.group(1))
        }
        return sudoCommand(commandList, sudoPassword)
    }

    fun sudoCommand(command: List<String>, sudoPassword: String?): String {
        return if (isRemote) {
            remoteSudoCommand(command, sudoPassword)
        } else {
            localSudoCommand(command, sudoPassword)
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

    private fun remoteSudoCommand(c: List<String>, sudoPassword: String?): String {
        lateinit var cmd: Session.Command
        lateinit var output: String
        lateinit var errorOutput: String
        val command = if (sudoPassword?.isNotBlank() == true) {
            " sudo -S -k " + c.joinToString(" ").trim() + " <<< '${sudoPassword}'"
        } else {
            " sudo -S -k \" + c.joinToString(\" \").trim()"
        }
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
        lateinit var commandList: List<String>
        val command = c.joinToString(" ").trim()
        val redirectIndex = c.lastIndexOf(">>")
        var redirectAppendFile = ""
        if (redirectIndex > -1) {
            redirectAppendFile = c[redirectIndex + 1]
            commandList = c.subList(0, redirectIndex)
        } else {
            commandList = c
        }
        commandList = commandList.map { clause -> clause.trim('\'') }
        try {
            process = ProcessBuilder(commandList).also {
                if (redirectAppendFile.isNotBlank()) {
                    it.redirectOutput(ProcessBuilder.Redirect.appendTo(File(redirectAppendFile)))
                }
            }.start()
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

    private fun localSudoCommand(c: List<String>, sudoPassword: String?): String {
        lateinit var process: Process
        lateinit var output: String
        lateinit var errorOutput: String
        lateinit var commandList: MutableList<String>
        val command = c.joinToString(" ").trim()
        val redirectIndex = c.lastIndexOf(">>")
        var redirectAppendFile = ""
        if (redirectIndex > -1) {
            redirectAppendFile = c[redirectIndex + 1]
            commandList = c.subList(0, redirectIndex).toMutableList()
        } else {
            commandList = c.toMutableList()
        }
        commandList = commandList.map { clause -> clause.trim('\'') }.toMutableList()
        commandList.addAll(0, listOf("sudo", "-S", "-k"))
        try {
            process = ProcessBuilder(commandList).also {
                if (redirectAppendFile.isNotBlank()) {
                    it.redirectOutput(ProcessBuilder.Redirect.appendTo(File(redirectAppendFile)))
                }
            }.start()
            if (sudoPassword?.isNotBlank() == true) {
                PrintWriter(process.outputStream.bufferedWriter()).use { it.println(sudoPassword) }
            }
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

    fun commandWriteFile(fileName: String, content: String): String {
        command("touch $fileName")
        command("chmod u+w $fileName")
        command("truncate -s 0 $fileName")
        content.split('\n').forEach { line ->
            command("printf '$line\\n' >> $fileName")
        }
        return "" // none of these command should have any output
    }

    fun sudoCommandWriteFile(fileName: String, content: String, sudoPassword: String?): String {
        val tempFileName = "/tmp/jormanager.tmp"
        commandWriteFile(tempFileName, content)
        command("chmod 644 $tempFileName")
        sudoCommand("chown root:root $tempFileName", sudoPassword ?: "")
        sudoCommand("mv -f $tempFileName $fileName", sudoPassword ?: "")
        return "" // none of these command should have any output
    }

    /**
     * Find a local available port to bind to for port forwarding.
     */
    private fun availableLocalPort(): Int {
        val random = Random(System.currentTimeMillis())
        while (true) {
            val port = random.nextInt(13000, 65534)
            try {
                ServerSocket(port).apply { reuseAddress = true }.use {
                    DatagramSocket(port).apply { reuseAddress = true }.use {
                        return port
                    }
                }
            } catch (e: IOException) {
            }
        }
    }
}