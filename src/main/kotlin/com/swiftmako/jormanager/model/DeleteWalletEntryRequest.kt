package com.swiftmako.jormanager.model


data class DeleteWalletEntryRequest(
        val spendingPassword: String,
        val id: Long,
)