package com.swiftmako.jormanager.repositories

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.model.QueryTip
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_SINGLETON
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Controller

@Controller
@Scope(SCOPE_SINGLETON)
class CardanoRepository
    @Autowired
    constructor(
        private val queryTipAdapter: JsonAdapter<QueryTip>
    ) {
        fun getEra(
            host: Host,
            node: Node,
            magicString: String,
            socketPath: String
        ): String {
            val hostConnection = HostConnection(host, node)
            val tipJson =
                hostConnection
                    .command("${host.cardanoCliPath} query tip $magicString $socketPath")
                    .trim()
            val tip = queryTipAdapter.fromJson(tipJson)
            val era = requireNotNull(tip?.era?.lowercase()) { "Era not found!" }
            return era
        }
    }
