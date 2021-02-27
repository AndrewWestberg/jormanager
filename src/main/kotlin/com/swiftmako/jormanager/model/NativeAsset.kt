package com.swiftmako.jormanager.model

import java.math.BigInteger

data class NativeAsset(
        val name: String,
        val policy: String,
        val amount: BigInteger,
)