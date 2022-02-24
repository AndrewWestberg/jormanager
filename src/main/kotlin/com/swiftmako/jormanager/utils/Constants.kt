package com.swiftmako.jormanager.utils

object Constants {
    const val ENTERPRISE_ADDRESS_PREFIX_MAINNET = 0x61.toByte()
    const val ENTERPRISE_ADDRESS_PREFIX_TESTNET = 0x60.toByte()
    const val STAKE_PAYMENT_ADDRESS_PREFIX_MAINNET = 0x01.toByte()
    const val STAKE_PAYMENT_ADDRESS_PREFIX_TESTNET = 0x00.toByte()
    const val STAKE_ADDRESS_PREFIX_MAINNET = 0xe1.toByte()
    const val STAKE_ADDRESS_PREFIX_TESTNET = 0xe0.toByte()
    const val SCRIPT_ADDRESS_PREFIX_MAINNET = 0x71.toByte()
    const val SCRIPT_ADDRESS_PREFIX_TESTNET = 0x70.toByte()
    const val SCRIPT2_ADDRESS_PREFIX_MAINNET = 0x31.toByte()
    const val SCRIPT2_ADDRESS_PREFIX_TESTNET = 0x30.toByte()
    const val STAKED_SCRIPT_ADDRESS_PREFIX_MAINNET = 0x11.toByte()
    const val STAKED_SCRIPT_ADDRESS_PREFIX_TESTNET = 0x10.toByte()
    const val BYRON_ADDRESS_PREFIX = 0x82.toByte()
    const val RECEIVE_ADDRESS_PATTERN = "^addr(_test)?1(?=[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)(?:.{98})$"
    const val STAKE_ADDRESS_PATTERN = "^stake(_test)?1(?=[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)(?:.{53})$"
}