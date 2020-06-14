package com.swiftmako.jormanager.controllers

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.SocketResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class HostControllerTest {

    @Test
    fun `test host controller remote connection success`() {
        val host = Host(
                type = "remote",
                hostname = "papa",
                sshUser = "westbam",
                sshPort = 15795,
                sshPemPath = "/home/westbam/.ssh/tux_private.pem",
                cardanoCliPath = "/home/westbam/.local/bin/cardano-cli",
                cardanoNodePath = "/home/westbam/.local/bin/cardano-node",
                nodeHomePath = "/home/westbam/haskell"
        )
        val target = HostController(mockk {
            every { save<Host>(any()) } returns host
        })
        val response = target.addHost(host)
        Thread.sleep(1000)
        println("-----")
        println(response)
        println("-----")
        assertThat(response).isInstanceOf(SocketResponse.Success::class.java)
    }

    @Test
    fun `test host controller local connection success`() {
        val host = Host(
                type = "local",
                hostname = "brainy",
                sshUser = "westbam",
                cardanoCliPath = "/home/westbam/.local/bin/cardano-cli",
                cardanoNodePath = "/home/westbam/.local/bin/cardano-node",
                nodeHomePath = "/home/westbam/haskell"
        )
        val target = HostController(mockk {
            every { save<Host>(any()) } returns host
        })
        val response = target.addHost(host)
        Thread.sleep(1000)
        println("-----")
        println(response)
        println("-----")
        assertThat(response).isInstanceOf(SocketResponse.Success::class.java)
    }

}