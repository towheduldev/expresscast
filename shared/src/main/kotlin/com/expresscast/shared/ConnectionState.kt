package com.expresscast.shared

/**
 * Lifecycle state of an ExpressCast session on either phone or TV.
 */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Discovering : ConnectionState
    data object Connecting : ConnectionState
    data object Pairing : ConnectionState
    data object Connected : ConnectionState
    data object Streaming : ConnectionState
    data class Reconnecting(val attempt: Int) : ConnectionState
    data object Disconnected : ConnectionState
    data class Failed(
        val error: ExpressCastError,
        val detail: String? = null
    ) : ConnectionState
}
