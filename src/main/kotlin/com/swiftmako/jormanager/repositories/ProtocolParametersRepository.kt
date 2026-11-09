package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import org.springframework.stereotype.Repository

@Repository
class ProtocolParametersRepository {
    fun query(host: Host, node: Node, connection: HostConnection, era: String, magic: String, socket: String): String {
        val raw = connection.commandArgs(listOf(host.cardanoCliPath, era, "query", "protocol-parameters") + magic.split(" ") + socket.split(" ") + "--output-json").trim()
        require(raw.isNotEmpty()) { "Protocol parameters unavailable" }
        return raw
    }
}
