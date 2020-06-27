package com.swiftmako.jormanager.controllers.utils

import com.swiftmako.jormanager.entities.Host
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

        val hostConnection = HostConnection(Host(0, "local", "","","","",22,"",""))
        hostConnection.sudoCommandWriteFile("/home/westbam/haskell/tickr-node.service", systemdContent, "******************")

    }
}