package com.swiftmako.jormanager.ktx

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborByteString
import org.springframework.security.crypto.codec.Hex
import java.math.BigInteger

fun ignoreExceptions(block: () -> Unit) {
    try {
        block.invoke()
    } catch (ignored: Throwable) {
    }
}

inline fun <T> Iterable<T>.sumByBigInteger(selector: (T) -> BigInteger): BigInteger {
    var sum = BigInteger.ZERO
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

fun CborArray.elementToLong(index: Int): Long {
    val obj = elementAt(index).toJavaObject()
    return (obj as? Long) ?: (obj as Int).toLong()
}

fun CborArray.elementToByteArray(index: Int): ByteArray {
    return (elementAt(index) as CborByteString).byteArrayValue()
}

fun CborArray.elementToHexString(index: Int): String {
    return elementToByteArray(index).toHexString()
}

fun ByteArray.toHexString(): String {
    return String(Hex.encode(this))
}

fun String.hexToByteArray(): ByteArray {
    return Hex.decode(this)
}