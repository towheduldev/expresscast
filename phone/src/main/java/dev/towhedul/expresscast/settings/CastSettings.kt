package dev.towhedul.expresscast.settings

import android.content.Context
import dev.towhedul.expresscast.shared.VideoStreamConfig

enum class ResolutionPreset(val label: String, val shortSidePx: Int) {
    P540("540p", 540),
    P720("720p", 720),
    P1080("1080p", 1080);

    companion object {
        fun fromName(name: String?): ResolutionPreset =
            entries.firstOrNull { it.name == name } ?: P720
    }
}

enum class AspectCropMode(val label: String, val ratio: Float?) {
    NATIVE("Native", null),
    RATIO_16_9("16:9 (Fill TV)", 16f / 9f),
    RATIO_16_10("16:10", 16f / 10f),
    RATIO_4_3("4:3", 4f / 3f);

    companion object {
        fun fromName(name: String?): AspectCropMode =
            entries.firstOrNull { it.name == name } ?: RATIO_16_9
    }
}

data class CastSettings(
    val resolution: ResolutionPreset = ResolutionPreset.P720,
    val fps: Int = 60,
    val bitrateMbps: Int = 6,
    val cropMode: AspectCropMode = AspectCropMode.RATIO_16_9,
    val audioEnabled: Boolean = true,
    val showStatsOverlay: Boolean = true,
    val adaptiveBitrate: Boolean = true
) {
    fun toVideoStreamConfig(displayWidth: Int, displayHeight: Int): VideoStreamConfig {
        val targetShort = resolution.shortSidePx
        // NATIVE streams over a 16:9 hardware-compatible canvas with FIT_CENTER scaling so both
        // portrait and landscape orientations rotate cleanly without squashing or encoder resets.
        val aspect = cropMode.ratio ?: (16f / 9f)

        val encodeHeight = makeEven(targetShort)
        val encodeWidth = makeEven((targetShort * aspect).toInt().coerceAtLeast(2))

        return VideoStreamConfig(
            width = encodeWidth,
            height = encodeHeight,
            fps = fps.coerceIn(1, 120),
            bitrateBps = (bitrateMbps.coerceIn(MIN_BITRATE_MBPS, MAX_BITRATE_MBPS) * 1_000_000)
                .coerceIn(1_000_000, 100_000_000),
            keyframeIntervalSec = 2
        )
    }

    companion object {
        val FPS_OPTIONS: List<Int> = listOf(15, 30, 60, 90, 120)
        const val MIN_BITRATE_MBPS: Int = 1
        const val MAX_BITRATE_MBPS: Int = 30
        val BITRATE_PRESETS_MBPS: List<Int> = listOf(2, 4, 6, 10, 15, 25)

        private fun makeEven(value: Int): Int = (value / 2) * 2
    }
}

class CastSettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): CastSettings {
        return CastSettings(
            resolution = ResolutionPreset.fromName(prefs.getString(KEY_RESOLUTION, null)),
            fps = prefs.getInt(KEY_FPS, 60).coerceIn(1, 120),
            bitrateMbps = prefs.getInt(KEY_BITRATE_MBPS, 6)
                .coerceIn(CastSettings.MIN_BITRATE_MBPS, CastSettings.MAX_BITRATE_MBPS),
            cropMode = AspectCropMode.fromName(prefs.getString(KEY_CROP_MODE, null)),
            audioEnabled = prefs.getBoolean(KEY_AUDIO_ENABLED, true),
            showStatsOverlay = prefs.getBoolean(KEY_SHOW_OVERLAY, true),
            adaptiveBitrate = prefs.getBoolean(KEY_ADAPTIVE_BITRATE, true)
        )
    }

    fun save(settings: CastSettings) {
        prefs.edit()
            .putString(KEY_RESOLUTION, settings.resolution.name)
            .putInt(KEY_FPS, settings.fps.coerceIn(1, 120))
            .putInt(KEY_BITRATE_MBPS, settings.bitrateMbps.coerceIn(CastSettings.MIN_BITRATE_MBPS, CastSettings.MAX_BITRATE_MBPS))
            .putString(KEY_CROP_MODE, settings.cropMode.name)
            .putBoolean(KEY_AUDIO_ENABLED, settings.audioEnabled)
            .putBoolean(KEY_SHOW_OVERLAY, settings.showStatsOverlay)
            .putBoolean(KEY_ADAPTIVE_BITRATE, settings.adaptiveBitrate)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "expresscast_settings"
        const val KEY_RESOLUTION = "resolution"
        const val KEY_FPS = "fps"
        const val KEY_BITRATE_MBPS = "bitrate_mbps"
        const val KEY_CROP_MODE = "crop_mode"
        const val KEY_AUDIO_ENABLED = "audio_enabled"
        const val KEY_SHOW_OVERLAY = "show_overlay"
        const val KEY_ADAPTIVE_BITRATE = "adaptive_bitrate"
    }
}
