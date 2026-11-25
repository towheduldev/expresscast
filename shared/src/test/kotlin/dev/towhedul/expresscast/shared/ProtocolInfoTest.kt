package dev.towhedul.expresscast.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolInfoTest {
    @Test
    fun isCompatible_acceptsSupportedRangeAndRejectsOutOfRange() {
        assertTrue(ProtocolInfo.isCompatible(1))
        assertTrue(ProtocolInfo.isCompatible(ProtocolInfo.PROTOCOL_VERSION))
        assertFalse(ProtocolInfo.isCompatible(0))
        assertFalse(ProtocolInfo.isCompatible(ProtocolInfo.PROTOCOL_VERSION + 1))
        assertEquals(1, ProtocolInfo.negotiatedVersion(1))
        assertEquals(ProtocolInfo.PROTOCOL_VERSION, ProtocolInfo.negotiatedVersion(99))
    }
}
