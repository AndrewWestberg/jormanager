package com.swiftmako.jormanager

import com.muquit.libsodiumjna.SodiumLibrary
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.model.ledger.Ledger
import com.swiftmako.jormanager.nodeclient.protocols.mux.MuxProtocol
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import org.junit.jupiter.api.Test
import java.io.File

class NodeConnectTest {

    @Test
    fun test() = runBlocking {
        MuxProtocol("localhost", 6000, 764824073).start().join()
    }

    @Test
    fun libsodiumTest() {
        // Linux
        val libraryPath = "/usr/local/lib/libsodium.so"
        println("Library path: $libraryPath")

        println("loading libsodium...")
        SodiumLibrary.setLibraryPath(libraryPath);

        val v = SodiumLibrary.libsodiumVersionString()
        println("libsodium version: $v")
    }

    @Test
    fun testLedgerState() {
        val moshi = Moshi.Builder().build()
        val ledgerAdapter = moshi.adapter(Ledger::class.java)

        val source = File("/tmp/ledger-state.json").source().buffer()

        val ledger = ledgerAdapter.fromJson(source)

        val delegations = ledger!!.esSnapshots.pstakeSet.delegations.filter { it[1] == "00beef0a9be2f6d897ed24a613cf547bb20cd282a04edfc53d477114" }.flatMap { ... }

        println("$delegations")
    }
}

