package com.swiftmako.jormanager.entities

sealed class SocketResponse<out T : Any> {
    data class Success<out T : Any>(val data: T) : SocketResponse<T>()
    data class Error(val exception: Exception) : SocketResponse<Nothing>()
}