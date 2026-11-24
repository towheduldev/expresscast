package dev.towhedul.expresscast.shared

/**
 * Video encoding parameters for the hardware H.264 pipeline.
 *
 * Streams use H.264 with B-frames disabled and low-latency settings when supported.
 * [keyframeIntervalSec] is kept relatively long because keyframes are requested on demand
 * over the control channel on connect or packet loss.
 */
data class VideoStreamConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val keyframeIntervalSec: Int
) {
    init {
        require(width > 0 && width % 2 == 0) {
            "width must be a positive even integer, got $width"
        }
        require(height > 0 && height % 2 == 0) {
            "height must be a positive even integer, got $height"
        }
        require(fps in 1..120) {
            "fps must be in 1..120, got $fps"
        }
        require(bitrateBps in 1_000_000..100_000_000) {
            "bitrateBps must be in 1..100 Mbps, got $bitrateBps"
        }
        require(keyframeIntervalSec >= 1) {
            "keyframeIntervalSec must be >= 1, got $keyframeIntervalSec"
        }
    }

    companion object {
        /** 720p at 60 FPS, 6 Mbps — balanced default for low-loss Wi-Fi streaming. */
        val HD_720P60: VideoStreamConfig = VideoStreamConfig(
            width = 1280,
            height = 720,
            fps = 60,
            bitrateBps = 6_000_000,
            keyframeIntervalSec = 2
        )

        /** 1080p at 60 FPS, 20 Mbps — high-resolution preset for strong 5 GHz/Wi-Fi 6 links. */
        val FHD_1080P60: VideoStreamConfig = VideoStreamConfig(
            width = 1920,
            height = 1080,
            fps = 60,
            bitrateBps = 20_000_000,
            keyframeIntervalSec = 2
        )

        /** Default configuration used at startup before user or adaptive changes. */
        val DEFAULT: VideoStreamConfig = HD_720P60
    }
}
