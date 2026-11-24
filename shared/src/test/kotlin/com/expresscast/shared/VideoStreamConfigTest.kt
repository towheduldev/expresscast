package com.expresscast.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoStreamConfigTest {
    @Test
    fun presets_areValid() {
        val hd = VideoStreamConfig.HD_720P60
        val fhd = VideoStreamConfig.FHD_1080P60

        assertEquals(1280, hd.width)
        assertEquals(720, hd.height)
        assertEquals(60, hd.fps)
        assertEquals(hd, VideoStreamConfig.DEFAULT)

        assertEquals(1920, fhd.width)
        assertEquals(1080, fhd.height)
        assertEquals(60, fhd.fps)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oddWidth_throwsIllegalArgumentException() {
        VideoStreamConfig(
            width = 1281,
            height = 720,
            fps = 60,
            bitrateBps = 12_000_000,
            keyframeIntervalSec = 2
        )
    }
}
