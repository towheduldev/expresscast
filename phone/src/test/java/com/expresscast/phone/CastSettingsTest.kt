package com.expresscast.phone.settings

import org.junit.Assert.assertEquals
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
    fun nativeAspectPreservesPortraitOrLandscapeOrientation() {
        val portrait = CastSettings(
            resolution = ResolutionPreset.P720,
            cropMode = AspectCropMode.NATIVE
        ).toVideoStreamConfig(displayWidth = 1080, displayHeight = 2160)

        assertEquals(720, portrait.width)
        assertEquals(1440, portrait.height)
    }
}
