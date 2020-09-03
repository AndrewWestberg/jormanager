package com.swiftmako.jormanager.model


data class CreateWalletEntryRequest(
        val spendingPassword: String,
        val name: String,
        val type: String,
        val paymentAddr: String,
        val generateKeys: Boolean,
        val paymentSKey: String?,
        val paymentVKey: String?,
        val stakingSKey: String?,
        val stakingVKey: String?
) {
    override fun toString(): String {
        return "CreateWalletEntryRequest(spendingPassword='************', name='$name', type='$type', paymentAddr='$paymentAddr', generateKeys=$generateKeys, paymentSKey=***, paymentVKey=***, stakingSKey=***, stakingVKey=***)"
    }
}