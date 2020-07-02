package com.swiftmako.jormanager.ktx

fun ignoreExceptions(block: () -> Unit) {
    try {
        block.invoke()
    } catch (ignored: Throwable) {
    }
}