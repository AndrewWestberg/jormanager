package com.swiftmako.jormanager.entities

sealed class SocketResponse<out T : Any> {
    data class Success<out T : Any>(
        val type: String,
        val data: T
    ) : SocketResponse<T>()

    data class Error(
        val type: String,
        val exception: PublicFailure
    ) : SocketResponse<Nothing>() {
        constructor(type: String, exception: Throwable) : this(
            type,
            PublicFailure(
                code = exception.javaClass.simpleName,
                message = "Operation failed; inspect the protected server diagnostics.",
                operation = type,
                retryDisposition = "review",
            ),
        )
    }

    data class PublicFailure(
        val code: String,
        val message: String,
        val operation: String,
        val retryDisposition: String,
        val requestId: String? = null,
        val target: String? = null,
    )
}
