package com.swiftmako.jormanager.model


data class CreateWalletEntryRequest(
        val name: String,
        val type: String,
        val paymentAddr: String,
        val generateKeys: Boolean,
        val paymentSKey: String?,
        val paymentVKey: String?,
        val stakingSKey: String?,
        val stakingVKey: String?
)