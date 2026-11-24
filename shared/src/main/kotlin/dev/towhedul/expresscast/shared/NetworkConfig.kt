package dev.towhedul.expresscast.shared

/**
 * Network defaults and timing parameters for LAN discovery, control, and video transport.
 */
object NetworkConfig {
    /** mDNS/NSD service type advertised by the phone streaming server. */
    const val NSD_SERVICE_TYPE: String = "_expresscast._tcp"

    /** Default TCP port for handshake, pairing, stats, and keyframe requests. */
    const val DEFAULT_CONTROL_PORT: Int = 47800

    /** Default UDP port for low-latency fragmented H.264 video packets. */
    const val DEFAULT_VIDEO_PORT: Int = 47801

    /** Default UDP port for low-latency PCM audio packets. */
    const val DEFAULT_AUDIO_PORT: Int = 47802

    /**
     * Conservative UDP datagram ceiling (bytes) to stay under typical 1500-byte Ethernet/Wi-Fi MTU
     * after IP (20 B) and UDP (8 B) headers without IP fragmentation.
     */
    const val MAX_DATAGRAM_SIZE: Int = 1400

    /** TCP control socket connection timeout in milliseconds. */
    const val CONNECT_TIMEOUT_MS: Long = 3000L

    /** Interval between control-channel keepalive heartbeats in milliseconds. */
    const val HEARTBEAT_INTERVAL_MS: Long = 1000L

    /** Elapsed time without a heartbeat before declaring the connection lost. */
    const val HEARTBEAT_TIMEOUT_MS: Long = 3000L
}
