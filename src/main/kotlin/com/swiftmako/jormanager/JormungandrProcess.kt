package com.swiftmako.jormanager

data class JormungandrProcess(
        val startedAt: Long,
        val process: Process,
        var firewallOpen:Boolean,
        val isPassive: Boolean
)