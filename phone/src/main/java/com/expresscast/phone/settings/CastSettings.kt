package com.expresscast.phone.settings

import android.content.Context
import com.expresscast.shared.VideoStreamConfig

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
    val cropMode: AspectCropMode = AspectCropMode.RATIO_16_9
) {
    fun toVideoStreamConfig(displayWidth: Int, displayHeight: Int): VideoStreamConfig {
        val targetShort = resolution.shortSidePx
        val aspect = cropMode.ratio ?: run {
            val w = displayWidth.coerceAtLeast(1).toFloat()
            val h = displayHeight.coerceAtLeast(1).toFloat()
            if (w >= h) w / h else h / w
        }

        val encodeHeight = makeEven(targetShort)
        val encodeWidth = makeEven((targetShort * aspect).toInt().coerceAtLeast(2))

        val (finalW, finalH) = if (cropMode == AspectCropMode.NATIVE && displayHeight > displayWidth) {
            encodeHeight to encodeWidth
        } else {
            encodeWidth to encodeHeight
        }

        return VideoStreamConfig(
            width = finalW,
            height = finalH,
            fps = fps.coerceIn(1, 120),
            bitrateBps = (bitrateMbps * 1_000_000).coerceIn(1_000_000, 100_000_000),
            keyframeIntervalSec = 2
        )
    }

    companion object {
        private fun makeEven(value: Int): Int = (value / 2) * 2
    }
}

class CastSettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): CastSettings {
        return CastSettings(
            resolution = ResolutionPreset.fromName(prefs.getString(KEY_RESOLUTION, null)),
            fps = prefs.getInt(KEY_FPS, 60),
            bitrateMbps = prefs.getInt(KEY_BITRATE_MBPS, 6),
            cropMode = AspectCropMode.fromName(prefs.getString(KEY_CROP_MODE, null))
        )
    }

    fun save(settings: CastSettings) {
        prefs.edit()
            .putString(KEY_RESOLUTION, settings.resolution.name)
            .putInt(KEY_FPS, settings.fps)
            .putInt(KEY_BITRATE_MBPS, settings.bitrateMbps)
            .putString(KEY_CROP_MODE, settings.cropMode.name)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "expresscast_settings"
        const val KEY_RESOLUTION = "resolution"
        const val KEY_FPS = "fps"
        const val KEY_BITRATE_MBPS = "bitrate_mbps"
        const val KEY_CROP_MODE = "crop_mode"
    }
}
