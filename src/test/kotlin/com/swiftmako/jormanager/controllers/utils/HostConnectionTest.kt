package com.swiftmako.jormanager.controllers.utils

import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import org.junit.jupiter.api.Test

class HostConnectionTest {

    @Test
    fun testMultilineStringToFile() {
        val systemdContent = """
                                |[Unit]
                                |Description=Cardano Haskell Node - tickr
                                |After=syslog.target
                                |StartLimitIntervalSec=0
                                |
                                |[Service]
                                |Type=simple
                                |Restart=always
                                |RestartSec=5
                                |User=westbam
                                |LimitNOFILE=131072
                                |WorkingDirectory=/home/westbam/haskell/tickr
                                |EnvironmentFile=/home/westbam/haskell/tickr/env
                                |ExecStart=/home/westbam/.local/bin/cardano-node \\
                                |  +RTS -N8 -RTS run \\
                                |  --topology ${'$'}{TOPOLOGY} \\
                                |  --database-path ${'$'}{DATABASE_PATH} \\
                                |  --socket-path ${'$'}{SOCKET_PATH} \\
                                |  --host-addr ${'$'}{HOST_ADDR} \\
                                |  --port ${'$'}{PORT} \\
                                |  --config ${'$'}{CONFIG}
                                |KillSignal="SIGINT"
                                |RestartKillSignal="SIGINT"
                                |StandardOutput=syslog
                                |StandardError=syslog
                                |SyslogIdentifier=tickr-node
                                |
                                |[Install]
                                |WantedBy=multi-user.target
                            """.trimMargin()

        val hostConnection = HostConnection(Host(0, "local", "", "", "", "", 22, "", ""))
        hostConnection.sudoCommandWriteFile("/home/westbam/haskell/tickr-node.service", systemdContent, "******************")
    }

    @Test
    fun testQueryAddress() {
        val host = Host(0, "local", "/home/westbam/.local/bin/cardano-cli", "", "", "", 22, "", "/home/westbam/haskell")
        val defaultNode = Node(0, 0, "", "relay", 8, "local", "127.0.0.1", 22, 12788, 0, 0, isDefault = true)
        val hostConnection = HostConnection(host, defaultNode)
        val output = hostConnection.command("${host.cardanoCliPath} shelley query utxo --address 60f9a5546c4d82ee112781dd02074a8b4a68e33ecced8a27e24bd2642b --testnet-magic 42")
        println(output)
    }
}