package com.swiftmako.jormanager.controllers.utils

import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.ignoreExceptions
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.xfer.FileSystemFile
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import org.slf4j.LoggerFactory
import java.io.File
import java.io.PrintWriter
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern

class HostConnection(
    private val host: Host,
    private val defaultNode: Node? = null
) {
    private val log by lazy { LoggerFactory.getLogger("HostConnection") }

    private val sshClientPool by lazy {
        SSHClientPool.getInstance(host)
    }

    val hasSystemd: Boolean by lazy {
        command("ps --no-headers -o comm 1").trim() == "systemd"
    }

    fun commandFileExists(filePath: String): Boolean =
        if (host.isRemote) {
            command("if test -f $filePath; then echo true; fi").trim().toBoolean()
        } else {
            File(filePath).exists()
        }

    fun command(command: String): String {
        val commandList = mutableListOf<String>()
        val m: Matcher = Pattern.compile("([^']\\S*|'.+?')\\s*").matcher(command)
        while (m.find()) {
            commandList.add(m.group(1))
        }
        return command(commandList)
    }

    fun command(command: List<String>): String =
        if (host.isRemote) {
            remoteCommand(command)
        } else {
            localCommand(command)
        }

    fun bashCommand(
        command: String,
        timeoutSecs: Long = 5L
    ): String =
        if (host.isRemote) {
            remoteBashCommand(command, timeoutSecs)
        } else {
            localBashCommand(command, timeoutSecs)
        }

    private fun localBashCommand(
        command: String,
        timeoutSecs: Long
    ): String {
        lateinit var output: String
        lateinit var errorOutput: String
        try {
            val process =
                ProcessBuilder(
                    "/bin/bash",
                    "-c",
                    command
                ).start()
            output =
                process.inputStream
                    .source()
                    .buffer()
                    .use { it.readUtf8() }
            errorOutput =
                process.errorStream
                    .source()
                    .buffer()
                    .use { it.readUtf8() }
            process.waitFor(timeoutSecs, TimeUnit.SECONDS)
            if (process.exitValue() != 0) {
                throw RuntimeException("Command '$command' exited with code ${process.exitValue()}: $errorOutput")
            }
        } catch (e: Throwable) {
            throw RuntimeException("Local command failed!", e)
        }
        return output
    }

    private fun remoteBashCommand(
        command: String,
        timeoutSecs: Long
    ): String {
        lateinit var output: String
        lateinit var errorOutput: String
        lateinit var ssh: SSHClient

        try {
            ssh = sshClientPool.borrow()
            ssh.startSession().use { session ->
                session.exec(command).use { cmd ->
                    output =
                        cmd.inputStream
                            .source()
                            .buffer()
                            .use { it.readUtf8() }
                    errorOutput =
                        cmd.errorStream
                            .source()
                            .buffer()
                            .use { it.readUtf8() }
                    cmd.join(timeoutSecs, TimeUnit.SECONDS)
                    if (cmd.exitStatus != 0) {
                        throw SSHRuntimeException("Command '$command' exited with code ${cmd.exitStatus}: ${cmd.exitErrorMessage}, $errorOutput")
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is SSHRuntimeException) {
                throw e
            }
            throw SSHRuntimeException("Error communicating with remote server!", e)
        } finally {
            ignoreExceptions { sshClientPool.recycle(ssh) }
        }
        return output
    }

    fun sudoCommand(
        command: String,
        sudoPassword: String?
    ): String {
        val commandList = mutableListOf<String>()
        val m: Matcher = Pattern.compile("([^']\\S*|'.+?')\\s*").matcher(command)
        while (m.find()) {
            commandList.add(m.group(1))
        }
        return sudoCommand(commandList, sudoPassword)
    }

    fun sudoCommand(
        command: List<String>,
        sudoPassword: String?
    ): String =
        if (host.isRemote) {
            remoteSudoCommand(command, sudoPassword)
        } else {
            localSudoCommand(command, sudoPassword)
        }

    private fun remoteCommand(c: List<String>): String {
        lateinit var output: String
        lateinit var errorOutput: String
        lateinit var ssh: SSHClient

        val command = c.joinToString(" ").trim()
        try {
            ssh = sshClientPool.borrow()
            ssh.startSession().use { session ->
                session.exec(command).use { cmd ->
                    output =
                        cmd.inputStream
                            .source()
                            .buffer()
                            .use { it.readUtf8() }
                    errorOutput =
                        cmd.errorStream
                            .source()
                            .buffer()
                            .use { it.readUtf8() }
                    cmd.join(5, TimeUnit.SECONDS)
                    if (cmd.exitStatus != 0) {
                        throw SSHRuntimeException("Command '$command' exited with code ${cmd.exitStatus}: ${cmd.exitErrorMessage}, $errorOutput")
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is SSHRuntimeException) {
                throw e
            }
            throw SSHRuntimeException("Error communicating with remote server!", e)
        } finally {
            ignoreExceptions { sshClientPool.recycle(ssh) }
        }
        return output
    }

    private fun remoteSudoCommand(
        c: List<String>,
        sudoPassword: String?
    ): String {
        lateinit var output: String
        lateinit var errorOutput: String
        lateinit var ssh: SSHClient

        val command =
            if (sudoPassword?.isNotBlank() == true) {
                " sudo -S -k " + c.joinToString(" ").trim() + " <<< '$sudoPassword'"
            } else {
                " sudo -S -k \" + c.joinToString(\" \").trim()"
            }
        try {
            ssh = sshClientPool.borrow()
            ssh.startSession().use { session ->
                session.exec(command).use { cmd ->
                    output =
                        cmd.inputStream
                            .source()
                            .buffer()
                            .use { it.readUtf8() }
                    errorOutput =
                        cmd.errorStream
                            .source()
                            .buffer()
                            .use { it.readUtf8() }
                    cmd.join(5, TimeUnit.SECONDS)
                    if (cmd.exitStatus != 0) {
                        throw SSHRuntimeException("Command '$command' exited with code ${cmd.exitStatus}: ${cmd.exitErrorMessage}, $errorOutput")
                    }
                }
            }
        } catch (e: Throwable) {
            throw SSHRuntimeException("Remote sudo command failed!", e)
        } finally {
            ignoreExceptions { sshClientPool.recycle(ssh) }
        }
        return output
    }

    private fun localCommand(c: List<String>): String {
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
            val process =
                ProcessBuilder(commandList)
                    .also {
                        if (redirectAppendFile.isNotBlank()) {
                            it.redirectOutput(ProcessBuilder.Redirect.appendTo(File(redirectAppendFile)))
                        }
                    }.start()
            output =
                process.inputStream
                    .source()
                    .buffer()
                    .use { it.readUtf8() }
            errorOutput =
                process.errorStream
                    .source()
                    .buffer()
                    .use { it.readUtf8() }
            process.waitFor(5, TimeUnit.SECONDS)
            if (process.exitValue() != 0) {
                throw RuntimeException("Command '$command' exited with code ${process.exitValue()}: $errorOutput")
            }
        } catch (e: Throwable) {
            throw RuntimeException("Local command failed!", e)
        }
        return output
    }

    private fun localSudoCommand(
        c: List<String>,
        sudoPassword: String?
    ): String {
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
            val process =
                ProcessBuilder(commandList)
                    .also {
                        if (redirectAppendFile.isNotBlank()) {
                            it.redirectOutput(ProcessBuilder.Redirect.appendTo(File(redirectAppendFile)))
                        }
                    }.start()
            if (sudoPassword?.isNotBlank() == true) {
                PrintWriter(process.outputStream.bufferedWriter()).use { it.println(sudoPassword) }
            }

            output =
                process.inputStream
                    .source()
                    .buffer()
                    .use { it.readUtf8() }
            errorOutput =
                process.errorStream
                    .source()
                    .buffer()
                    .use { it.readUtf8() }
            process.waitFor(5, TimeUnit.SECONDS)
            if (process.exitValue() != 0) {
                throw RuntimeException("Command '$command' exited with code ${process.exitValue()}: $errorOutput")
            }
        } catch (e: Throwable) {
            throw RuntimeException("Local sudo command failed!", e)
        }
        return output
    }

    fun commandGetFileBufferedSource(fileName: String): BufferedSource =
        if (host.isRemote) {
            remoteCommandGetFileBufferedSource(fileName)
        } else {
            localCommandGetFileBufferedSource(fileName)
        }

    private fun remoteCommandGetFileBufferedSource(fileName: String): BufferedSource {
        lateinit var ssh: SSHClient
        try {
            ssh = sshClientPool.borrow()
            ssh.newSCPFileTransfer().download(fileName, "/tmp/jm_scp_download_file.tmp")
            return localCommandGetFileBufferedSource("/tmp/jm_scp_download_file.tmp")
        } catch (e: Throwable) {
            if (e is SSHRuntimeException) {
                throw e
            }
            throw SSHRuntimeException("Error communicating with remote server!", e)
        } finally {
            ignoreExceptions {
                File("/tmp/jm_scp_download_file.tmp").delete()
            }
            ignoreExceptions { sshClientPool.recycle(ssh) }
        }
    }

    private fun localCommandGetFileBufferedSource(fileName: String): BufferedSource = File(fileName).source().buffer()

    fun commandReadFile(fileName: String): String =
        if (host.isRemote) {
            remoteCommandReadFile(fileName)
        } else {
            localCommandReadFile(fileName)
        }

    private fun localCommandReadFile(fileName: String): String = File(fileName).source().buffer().use { it.readUtf8() }

    private fun remoteCommandReadFile(fileName: String): String {
        lateinit var ssh: SSHClient
        try {
            ssh = sshClientPool.borrow()
            ssh.newSCPFileTransfer().download(fileName, "/tmp/jm_scp_download_file.tmp")
            return localCommandReadFile("/tmp/jm_scp_download_file.tmp")
        } catch (e: Throwable) {
            if (e is SSHRuntimeException) {
                throw e
            }
            throw SSHRuntimeException("Error communicating with remote server!", e)
        } finally {
            ignoreExceptions {
                File("/tmp/jm_scp_download_file.tmp").delete()
            }
            ignoreExceptions { sshClientPool.recycle(ssh) }
        }
    }

    fun commandWriteFile(
        fileName: String,
        content: String
    ): String {
        command("touch $fileName")
        command("chmod u+w $fileName")
        command("truncate -s 0 $fileName")
        if (host.isRemote) {
            remoteCommandWriteFile(fileName, content)
        } else {
            localCommandWriteFile(fileName, content)
        }
        return ""
    }

    private fun localCommandWriteFile(
        fileName: String,
        content: String
    ) {
        File(fileName).sink().buffer().use { it.writeUtf8(content) }
    }

    private fun remoteCommandWriteFile(
        fileName: String,
        content: String
    ) {
        lateinit var ssh: SSHClient
        try {
            ssh = sshClientPool.borrow()
            localCommandWriteFile("/tmp/jm_scp_file.tmp", content)
            ssh.newSCPFileTransfer().upload(FileSystemFile("/tmp/jm_scp_file.tmp"), fileName)
        } catch (e: Throwable) {
            if (e is SSHRuntimeException) {
                throw e
            }
            throw SSHRuntimeException("Error communicating with remote server!", e)
        } finally {
            ignoreExceptions {
                File("/tmp/jm_scp_file.tmp").delete()
            }
            ignoreExceptions { sshClientPool.recycle(ssh) }
        }
    }

    fun sudoCommandWriteFile(
        fileName: String,
        content: String,
        sudoPassword: String?
    ): String {
        val tempFileName = "/tmp/jormanager.tmp"
        commandWriteFile(tempFileName, content)
        command("chmod 644 $tempFileName")
        sudoCommand("chown root:root $tempFileName", sudoPassword ?: "")
        sudoCommand("mv -f $tempFileName $fileName", sudoPassword ?: "")
        return "" // none of these command should have any output
    }
}
