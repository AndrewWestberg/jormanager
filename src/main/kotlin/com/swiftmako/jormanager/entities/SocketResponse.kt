package com.swiftmako.jormanager.entities

sealed class SocketResponse<out T : Any> {
    data class Success<out T : Any>(
        val type: String,
        val data: T
    ) : SocketResponse<T>()

    data class Error(
        val type: String,
        val exception: Throwable
    ) : SocketResponse<Nothing>()
}
