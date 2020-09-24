package com.swiftmako.jormanager

import com.swiftmako.jormanager.nodeclient.protocols.mux.MuxProtocol
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class NodeConnectTest {

    @Test
    fun test() = runBlocking {
        MuxProtocol("localhost", 6000, 764824073).start().join()
    }
}

