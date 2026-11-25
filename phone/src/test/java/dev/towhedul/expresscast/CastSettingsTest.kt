package dev.towhedul.expresscast.settings

import dev.towhedul.expresscast.codec.HardwareH264Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastSettingsTest {
    @Test
    fun ratio16By9ProducesStandardHdAndFhdDimensions() {
        val hd = CastSettings(
            resolution = ResolutionPreset.P720,
            fps = 60,
            bitrateMbps = 6,
            cropMode = AspectCropMode.RATIO_16_9
        ).toVideoStreamConfig(displayWidth = 1080, displayHeight = 2400)

        assertEquals(1280, hd.width)
        assertEquals(720, hd.height)
        assertEquals(60, hd.fps)
        assertEquals(6_000_000, hd.bitrateBps)

        val fhd = CastSettings(
            resolution = ResolutionPreset.P1080,
            fps = 30,
            bitrateMbps = 10,
            cropMode = AspectCropMode.RATIO_16_9
        ).toVideoStreamConfig(displayWidth = 1080, displayHeight = 2400)

        assertEquals(1920, fhd.width)
        assertEquals(1080, fhd.height)
    }

    @Test
    fun nativeAspectUses16By9EncoderCanvasRegardlessOfOrientation() {
        val portrait = CastSettings(
            resolution = ResolutionPreset.P720,
            cropMode = AspectCropMode.NATIVE
        ).toVideoStreamConfig(displayWidth = 1080, displayHeight = 2160)

        assertEquals(1280, portrait.width)
        assertEquals(720, portrait.height)
    }

    @Test
    fun supportsSteady15FpsHighFpsAndCustomBitrateBounds() {
        val lowFps = CastSettings(
            resolution = ResolutionPreset.P540,
            fps = 15,
            bitrateMbps = 1
        ).toVideoStreamConfig(1080, 2400)
        assertEquals(15, lowFps.fps)
        assertEquals(1_000_000, lowFps.bitrateBps)

        val highFps = CastSettings(
            resolution = ResolutionPreset.P720,
            fps = 120,
            bitrateMbps = 30
        ).toVideoStreamConfig(1080, 2400)
        assertEquals(120, highFps.fps)
        assertEquals(30_000_000, highFps.bitrateBps)
        assertTrue(CastSettings().adaptiveBitrate)
    }

    @Test
    fun capBitrateForFpsPreventsOversizedLowFpsFrames() {
        val low15 = CastSettings(fps = 15, bitrateMbps = 10).toVideoStreamConfig(1080, 2400)
        val capped15 = HardwareH264Encoder.capBitrateForFps(low15)
        assertEquals(4_000_080, capped15.bitrateBps)

        val med30 = CastSettings(fps = 30, bitrateMbps = 15).toVideoStreamConfig(1080, 2400)
        val capped30 = HardwareH264Encoder.capBitrateForFps(med30)
        assertEquals(8_000_160, capped30.bitrateBps)

        val high60 = CastSettings(fps = 60, bitrateMbps = 15).toVideoStreamConfig(1080, 2400)
        assertEquals(15_000_000, HardwareH264Encoder.capBitrateForFps(high60).bitrateBps)
    }
}
