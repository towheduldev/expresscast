package dev.towhedul.expresscast.shared

/**
 * Lightweight UDP LAN beacon protocol used as a zero-config fallback when mDNS/NSD is
 * blocked by routers or mesh APs.
 */
object LanDiscoveryProtocol {
    private const val PROBE_PREFIX = "EC_DISCOVER"
    private const val ANNOUNCE_PREFIX = "EC_ANNOUNCE"
    private const val MAX_NAME_CHARS = 64

    data class LanAnnouncement(
        val protocolVersion: Int,
        val controlPort: Int,
        val deviceName: String
    )

    fun encodeProbe(): ByteArray = "$PROBE_PREFIX|1".toByteArray(Charsets.UTF_8)

    fun isProbe(data: ByteArray, length: Int = data.size): Boolean {
        if (length <= 0 || length > data.size || length > 64) return false
        val text = String(data, 0, length, Charsets.UTF_8).trim()
        return text == "$PROBE_PREFIX|1"
    }

    fun encodeAnnounce(
        deviceName: String,
        controlPort: Int,
        protocolVersion: Int = ProtocolInfo.PROTOCOL_VERSION
    ): ByteArray {
        val cleanName = deviceName
            .replace("|", " ")
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()
            .ifEmpty { ProtocolInfo.APP_NAME }
            .take(MAX_NAME_CHARS)
        return "$ANNOUNCE_PREFIX|$protocolVersion|$controlPort|$cleanName".toByteArray(Charsets.UTF_8)
    }

    fun decodeAnnounce(data: ByteArray, length: Int = data.size): LanAnnouncement? {
        if (length <= 0 || length > data.size || length > 512) return null
        val text = String(data, 0, length, Charsets.UTF_8).trim()
        val parts = text.split('|', limit = 4)
        if (parts.size != 4 || parts[0] != ANNOUNCE_PREFIX) return null
        val version = parts[1].toIntOrNull() ?: return null
        val port = parts[2].toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        val name = parts[3].trim().take(MAX_NAME_CHARS).ifEmpty { ProtocolInfo.APP_NAME }
        return LanAnnouncement(
            protocolVersion = version,
            controlPort = port,
            deviceName = name
        )
    }
}
