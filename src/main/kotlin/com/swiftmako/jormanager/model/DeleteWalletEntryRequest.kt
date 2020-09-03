package com.swiftmako.jormanager.model


data class DeleteWalletEntryRequest(
        val spendingPassword: String,
        val id: Long,
) {
    override fun toString(): String {
        return "DeleteWalletEntryRequest(spendingPassword='************', id=$id)"
    }
}