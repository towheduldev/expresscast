package dev.towhedul.expresscast.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolInfoTest {
    @Test
    fun isCompatible_acceptsCurrentVersionAndRejectsOutOfRange() {
        assertTrue(ProtocolInfo.isCompatible(ProtocolInfo.PROTOCOL_VERSION))
        assertFalse(ProtocolInfo.isCompatible(0))
        assertFalse(ProtocolInfo.isCompatible(ProtocolInfo.PROTOCOL_VERSION + 1))
    }
}
