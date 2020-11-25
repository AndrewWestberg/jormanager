package com.swiftmako.jormanager.model


data class RetirePoolRequest(
        val id: Long,
        val sudoPassword: String,
        val spendingPassword: String,
        val retireFeesAccount: Long,
        val retireEpoch: Long,
) {
    override fun toString(): String {
        return "RetirePoolRequest(id=$id, spendingPassword='********', retireFeesAccount=$retireFeesAccount, retireEpoch=$retireEpoch)"
    }
}