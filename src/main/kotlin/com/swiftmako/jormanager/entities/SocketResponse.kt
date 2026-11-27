package com.swiftmako.jormanager.entities

import com.swiftmako.jormanager.controllers.utils.HostKeyMismatchException

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
            findHostKeyMismatch(exception)?.let { mismatch ->
                PublicFailure(
                    code = "HOST_KEY_MISMATCH",
                    message = mismatch.message ?: HostKeyMismatchException.buildMessage(
                        mismatch.hostname,
                        mismatch.sshPort,
                        mismatch.expectedFingerprint,
                        mismatch.receivedFingerprint
                    ),
                    operation = type,
                    retryDisposition = "abort"
                )
            } ?: PublicFailure(
                code = exception.javaClass.simpleName,
                message = "Operation failed; inspect the protected server diagnostics.",
                operation = type,
                retryDisposition = "retry",
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

    companion object {
        private fun findHostKeyMismatch(error: Throwable?): HostKeyMismatchException? {
            var current = error
            val visited = HashSet<Throwable>()
            while (current != null && visited.add(current)) {
                if (current is HostKeyMismatchException) {
                    return current
                }
                current = current.cause
            }
            return null
        }
    }
}
