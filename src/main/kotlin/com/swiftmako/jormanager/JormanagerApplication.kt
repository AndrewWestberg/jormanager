package com.swiftmako.jormanager

import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.repositories.FileRepository
import java.io.File
import kotlin.system.exitProcess
import okio.buffer
import okio.sink
import okio.source
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.runApplication
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

@SpringBootApplication
class JormanagerApplication

val passwordEncoder: PasswordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()

fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        when (args[0]) {
            "install" -> runInstallation()
            "repair" -> {
                System.setProperty("jormanager.mode", "repair")
                val uri = JormanagerApplication::class.java.protectionDomain.codeSource.location.toURI().toString()
                    .substringAfterLast(":").substringBeforeLast("/jormanager")
                val jormanagerFolderPath = File(uri).absolutePath
                val applicationPropertiesPath = "$jormanagerFolderPath${File.separator}application.properties"

                val app = SpringApplicationBuilder(JormanagerApplication::class.java)
                    .web(WebApplicationType.NONE)
                    .run(*args)

                val fileRepository = app.getBean(FileRepository::class.java)
                val walletUtils = app.getBean(WalletUtils::class.java)
                runRepair(fileRepository, walletUtils, applicationPropertiesPath)
                app.close()
                exitProcess(0)
            }

            else -> runApplication<JormanagerApplication>(*args)
        }
    } else {
        runApplication<JormanagerApplication>(*args)
    }
}

fun runRepair(
    fileRepository: FileRepository,
    walletUtils: WalletUtils,
    applicationPropertiesPath: String
) {
    val console = System.console()
    println("--- Spending Password Repair ---")
    print("Specify your new JorManager spending password: ")
    val newSpendingPassword = String(console.readPassword())
    print("Verify your new JorManager spending password: ")
    val newSpendingPassword2 = String(console.readPassword())
    if (newSpendingPassword != newSpendingPassword2) {
        println("Passwords do not match! Aborting.")
        return
    }

    val oldPasswords = mutableListOf<String>()
    println("Enter previous spending passwords, one per line. Press Enter on an empty line to continue.")
    while (true) {
        print("Previous password: ")
        val oldPassword = String(console.readPassword())
        if (oldPassword.isBlank()) {
            break
        }
        oldPasswords.add(oldPassword)
    }

    val skeyFiles = fileRepository.findByNameLike("%skey%")
    if (skeyFiles.isEmpty()) {
        println("No skey files found. Aborting repair.")
        return
    }
    println("Found ${skeyFiles.size} skey files.")

    var successCount = 0
    var failCount = 0

    val passwordsToTry = listOf(newSpendingPassword) + oldPasswords

    skeyFiles.forEach { skeyFile ->

        var decryptedContent: String? = null

        for (password in passwordsToTry) {
            try {
                decryptedContent = walletUtils.decryptSKeyContentForRepair(skeyFile.content, password)
                if (decryptedContent != null) {
                    println("Successfully decrypted ${skeyFile.name}.")
                    break
                }
            } catch (_: Exception) {
                // Decryption failed, try next password
            }
        }

        if (decryptedContent != null) {
            try {
                val encryptedContent = walletUtils.encryptSKeyContentForRepair(decryptedContent, newSpendingPassword)
                fileRepository.save(skeyFile.copy(content = encryptedContent))
                println("Successfully re-encrypted and saved ${skeyFile.name}.")
                successCount++
            } catch (_: Exception) {
                println("Failed to re-encrypt ${skeyFile.name} with the new spending password.")
                failCount++
            }
        } else {
            println("Failed to decrypt ${skeyFile.name} with all provided passwords.")
            failCount++
        }
    }

    println("Repair process finished. Success: $successCount, Failed: $failCount.")

    if (successCount > 0 || failCount == 0) {
        updateApplicationProperties(applicationPropertiesPath, newSpendingPassword)
    } else {
        println("No skeys were successfully re-encrypted. Application properties will not be updated.")
    }
}

private fun updateApplicationProperties(
    applicationPropertiesPath: String,
    newSpendingPassword: String
) {
    val propertiesFile = File(applicationPropertiesPath)
    val lines = propertiesFile.source().buffer().readUtf8().lines().toMutableList()
    val newHash = passwordEncoder.encode(newSpendingPassword)

    val oldHashIndex = lines.indexOfFirst { it.startsWith("jormanager.spendingpassword=") }
    if (oldHashIndex != -1) {
        lines[oldHashIndex] = "#${lines[oldHashIndex]}"
    }

    lines.add("jormanager.spendingpassword=$newHash")

    propertiesFile.sink().buffer().use { sink ->
        lines.forEach { sink.writeUtf8(it).writeUtf8("\n") }
    }

    println("application.properties updated successfully.")
    println("New spending password: '$newSpendingPassword'")
    println("New spending password hash: jormanager.spendingpassword=$newHash")
}

fun runInstallation() {
    val uri = JormanagerApplication::class.java.protectionDomain.codeSource.location.toURI().toString()
        .substringAfterLast(":").substringBeforeLast("/jormanager")
    val jormanagerFolderPath = File(uri).absolutePath

    val applicationProperties = StringBuilder()
    val console = System.console()
    println("Welcome to JorManager!")
    println("----------------------")
    println("Please keep the listen IP address at the default value unless you REALLY know what the hell you're doing!")
    print("Specify listen IP address or Enter to accept default [127.0.0.1]: ")
    val listenIp = console.readLine().trim()
    if (listenIp.isNotBlank()) {
        applicationProperties.append("server.address=$listenIp\n")
    }
    print("Specify listen port or Enter to accept default [8787]: ")
    val port = console.readLine().trim()
    if (port.isNotBlank()) {
        applicationProperties.append("server.port=$port\n")
    }
    print("Specify your pooltool API Key found at -> https://pooltool.io/profile or Enter to accept default [None]: ")
    val pooltoolApiKey = console.readLine().trim()
    applicationProperties.append("pooltool.apikey=$pooltoolApiKey\n")

    print("Specify your JorManager spending password: ")
    val spendingPassword = String(console.readPassword())
    print("Verify your JorManager spending password: ")
    val spendingPassword2 = String(console.readPassword())
    if (spendingPassword != spendingPassword2) {
        println("Passwords do not match! Try installation again.")
        return
    }

    val encodedPassword = passwordEncoder.encode(spendingPassword)
    applicationProperties.append("jormanager.spendingpassword=$encodedPassword")

    File("$jormanagerFolderPath${File.separator}application.properties").sink().buffer()
        .use { it.writeUtf8(applicationProperties.toString()) }
    println("application.properties successfully created!")
    println()

    val host = Host(0, "local", "", "", "", "", 22, "", "", "")
    val defaultNode =
        Node(0, 0, null, "", "relay", 8, "local", "127.0.0.1", 22, 12788, 12789, 0, 0, 0, 0, 0, isDefault = true)
    val hostConnection = HostConnection(host, defaultNode)
    val javaPath = hostConnection.command("which java").trim()

    val startScriptPath = "$jormanagerFolderPath${File.separator}startJormanager.sh"
    File(startScriptPath).sink().buffer().use {
        it.writeUtf8(
            """
                |#!/bin/bash
                |OLDPWD=`pwd`
                |cd $jormanagerFolderPath
                |nohup $javaPath -XX:+DisableAttachMechanism -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -Xmx4096m -jar jormanager.jar > jormanager.log 2>&1 &
                |echo ${'$'}! > jormanager.pid
                |cd ${'$'}OLDPWD
                |echo "JorManager Started with logfile jormanager.log"
                """.trimMargin()
        )
    }
    val stopScriptPath = "$jormanagerFolderPath${File.separator}stopJormanager.sh"
    File(stopScriptPath).sink().buffer().use {
        it.writeUtf8(
            """
                |#!/bin/bash
                |OLDPWD=`pwd`
                |cd $jormanagerFolderPath
                |kill -INT `cat jormanager.pid` >/dev/null 2>&1
                |sleep 1
                |kill -TERM `cat jormanager.pid` >/dev/null 2>&1
                |sleep 1
                |kill -KILL `cat jormanager.pid` >/dev/null 2>&1
                |rm -f jormanager.pid
                |cd ${'$'}OLDPWD
                |echo "JorManager Stopped"
                """.trimMargin()
        )
    }
    hostConnection.command("chmod 700 $startScriptPath")
    hostConnection.command("chmod 700 $stopScriptPath")

    if (!hostConnection.hasSystemd) {
        println("WARN: systemd scripts are not available on your system.")
    } else {
        print("Specify the service name for the JorManager systemd startup script or Enter to accept default [jm.service]: ")
        var systemdServiceName = console.readLine().trim()
        systemdServiceName = systemdServiceName.ifBlank { "jm.service" }

        val user = hostConnection.command("whoami").trim()

        print("Enter your sudo password to allow installer to modify systemd scripts: ")
        val sudoPassword = String(console.readPassword())
        hostConnection.sudoCommandWriteFile(
            "/etc/systemd/system/$systemdServiceName", """
                |[Unit]
                |Description=JorManager - Manager for Cardano Nodes
                |After=syslog.target
                |StartLimitIntervalSec=0
                |
                |[Service]
                |Type=simple
                |Restart=always
                |RestartSec=5
                |User=$user
                |LimitNOFILE=131072
                |WorkingDirectory=$jormanagerFolderPath
                |ExecStart=/usr/bin/java -XX:+DisableAttachMechanism -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -Xmx4096m -jar jormanager.jar
                |SuccessExitStatus=143
                |SyslogIdentifier=${systemdServiceName.substringBefore('.')}
                |                
                |[Install]
                |WantedBy=multi-user.target
                """.trimMargin(),
            sudoPassword
        )
        hostConnection.sudoCommand("systemctl daemon-reload", sudoPassword)
        hostConnection.sudoCommand("systemctl stop $systemdServiceName", sudoPassword)
        hostConnection.sudoCommand("systemctl enable $systemdServiceName", sudoPassword)

        println("To start, run: $ sudo systemctl start $systemdServiceName")
        println("To stop, run: $ sudo systemctl stop $systemdServiceName")
    }
    println("To start manually, run: $ ./startJormanager.sh")
    println("To stop manually, run: $ ./stopJormanager.sh")
}
