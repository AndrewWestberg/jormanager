package com.swiftmako.jormanager.ktx

fun ignoreExceptions(block: () -> Unit) {
    try {
        block.invoke()
    } catch (ignored: Throwable) {
    }
}

inline fun <T> Iterable<T>.sumByLong(selector: (T) -> Long): Long {
    var sum = 0L
    for (element in this) {
        sum += selector(element)
    }
    return sum
}