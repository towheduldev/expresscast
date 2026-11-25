package dev.towhedul.expresscast.shared

/**
 * Protocol versioning and packet framing identity shared by phone and TV.
 */
object ProtocolInfo {
    /** Current wire protocol version. */
    const val PROTOCOL_VERSION: Int = 2

    /** Minimum remote protocol version accepted during handshake. */
    const val MIN_COMPATIBLE_PROTOCOL_VERSION: Int = 1

    /** Two-byte ASCII magic header ("EC") identifying ExpressCast video packets. */
    const val MAGIC: Short = 0x4543

    /** Human-readable application name used in logs and service metadata. */
    const val APP_NAME: String = "ExpressCast"

    /** Returns true when [remoteVersion] falls within our supported protocol range. */
    fun isCompatible(remoteVersion: Int): Boolean {
        return remoteVersion in MIN_COMPATIBLE_PROTOCOL_VERSION..PROTOCOL_VERSION
    }

    /** Returns the highest protocol version supported by both endpoints. */
    fun negotiatedVersion(remoteVersion: Int): Int {
        return minOf(PROTOCOL_VERSION, remoteVersion)
    }
}
