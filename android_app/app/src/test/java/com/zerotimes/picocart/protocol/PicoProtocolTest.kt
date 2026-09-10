package com.zerotimes.picocart.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class PicoProtocolTest {
    @Test
    fun compareVersionsOrdersNumericFirmware() {
        assertTrue(PicoProtocol.compareVersions("0.2.5", "0.2.4") > 0)
        assertEquals(0, PicoProtocol.compareVersions("0.2.5", "0.2.5"))
        assertTrue(PicoProtocol.compareVersions("0.2.5", "0.2.10") < 0)
    }

    @Test
    fun crc32MatchesPythonBinascii() {
        val payload = "FIRMWARE_VERSION = \"0.2.5\"\nclass CartController".toByteArray(Charsets.UTF_8)
        val expected = CRC32().also { it.update(payload) }.value
        assertEquals(expected, PicoProtocol.crc32(payload).toLong() and 0xFFFFFFFFL)
        assertEquals("%08x".format(expected), PicoProtocol.crc32Hex(payload))
    }

    @Test
    fun extractFirmwareVersionFromMain() {
        val source = "PROTOCOL_VERSION = \"x\"\nFIRMWARE_VERSION = \"0.2.5\"\n"
        assertEquals("0.2.5", PicoProtocol.extractFirmwareVersion(source))
    }
}
