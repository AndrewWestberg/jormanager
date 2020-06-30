package com.swiftmako.jormanager.model

data class NodeStats(
        val timestamp: Long,
        val nodeName: String,
        val color: String,
        val peers:Int?,
        val blockHeight: Long?
)