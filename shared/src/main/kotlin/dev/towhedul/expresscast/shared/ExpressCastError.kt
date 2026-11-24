package dev.towhedul.expresscast.shared

/**
 * Stable wire error codes shared between phone and TV.
 */
enum class ExpressCastError(val code: Int) {
    PROTOCOL_MISMATCH(1),
    PAIRING_REJECTED(2),
    TIMEOUT(3),
    NETWORK_UNAVAILABLE(4),
    CONNECTION_LOST(5),
    CODEC_UNSUPPORTED(6),
    PROJECTION_DENIED(7),
    INTERNAL(99);

    companion object {
        /** Maps a numeric wire [code] back to [ExpressCastError], defaulting to [INTERNAL]. */
        fun fromCode(code: Int): ExpressCastError {
            return entries.firstOrNull { it.code == code } ?: INTERNAL
        }
    }
}
