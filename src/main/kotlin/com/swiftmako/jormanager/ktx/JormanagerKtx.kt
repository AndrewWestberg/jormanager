package com.swiftmako.jormanager.ktx

import com.google.iot.cbor.CborArray

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

fun CborArray.elementToLong(index: Int): Long {
    val obj = elementAt(index).toJavaObject()
    return (obj as? Long) ?: (obj as Int).toLong()
}