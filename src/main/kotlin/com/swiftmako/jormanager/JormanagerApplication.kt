package com.swiftmako.jormanager

import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import okio.buffer
import okio.sink
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.io.File

@SpringBootApplication
class JormanagerApplication

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "install") {
        runInstallation()
    } else {
        runApplication<JormanagerApplication>(*args)
    }
}

val passwordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()

fun runInstallation() {
    val uri = JormanagerApplication::class.java.protectionDomain.codeSource.location.toURI().toString()
        .substringAfter("file:").substringBeforeLast("/jormanager")
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
        Node(0, 0, null, "", "relay", 8, "local", "127.0.0.1", 22, 12788, 12789, 0, 0, 0, 0, isDefault = true)
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
                |StandardOutput=syslog
                |StandardError=syslog
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
