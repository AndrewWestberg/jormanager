package com.swiftmako.jormanager.model

data class UpdateStakingAddressRequest(
    val id: Long,
    val spendingPassword: String,
    val stakingFeesAccount: Long,
    val isRegistration: Boolean,
) {
    override fun toString(): String = "UpdateStakingAddressRequest(id=$id, spendingPassword='********', stakingFeesAccount=$stakingFeesAccount, isRegistration=$isRegistration)"
}
