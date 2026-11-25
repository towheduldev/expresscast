package dev.towhedul.expresscast.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanDiscoveryProtocolTest {
    @Test
    fun probe_roundTripAndValidation() {
        val probe = LanDiscoveryProtocol.encodeProbe()
        assertTrue(LanDiscoveryProtocol.isProbe(probe))
        assertFalse(LanDiscoveryProtocol.isProbe("OTHER|1".toByteArray()))
        assertFalse(LanDiscoveryProtocol.isProbe(ByteArray(0)))
    }

    @Test
    fun announce_roundTripAndSanitization() {
        val payload = LanDiscoveryProtocol.encodeAnnounce(
            deviceName = "Pixel|8\nPro",
            controlPort = 47800,
            protocolVersion = 2
        )
        val decoded = LanDiscoveryProtocol.decodeAnnounce(payload)
        assertNotNull(decoded)
        assertEquals(2, decoded!!.protocolVersion)
        assertEquals(47800, decoded.controlPort)
        assertEquals("Pixel 8 Pro", decoded.deviceName)
    }

    @Test
    fun announce_rejectsInvalidPortOrFormat() {
        assertNull(LanDiscoveryProtocol.decodeAnnounce("EC_ANNOUNCE|2|0|Phone".toByteArray()))
        assertNull(LanDiscoveryProtocol.decodeAnnounce("EC_ANNOUNCE|2|70000|Phone".toByteArray()))
        assertNull(LanDiscoveryProtocol.decodeAnnounce("BAD_PREFIX|2|47800|Phone".toByteArray()))
        assertNull(LanDiscoveryProtocol.decodeAnnounce(ByteArray(0)))
    }
}
