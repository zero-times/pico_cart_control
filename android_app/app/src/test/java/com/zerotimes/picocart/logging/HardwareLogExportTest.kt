package com.zerotimes.picocart.logging

import org.junit.Assert.*
import org.junit.Test

class HardwareLogExportTest {
    private fun header(n: Int = 2, x: Int = 7, last: Int = 11) = "ok hwlog_export n=$n x=$x last=$last fw=0.2.0"
    private fun record(i: Int, n: Int = 2, x: Int = 7, s: Int = 9 + i) =
        "hwlog x=$x i=$i n=$n s=$s t=12 u=0 fw=0.2.0 e=boot"
    private fun end(n: Int = 2, x: Int = 7, last: Int = 11) = "hwlog_end n=$n x=$x last=$last fw=0.2.0"

    @Test fun completeExportRequiresMatchingEndAndEveryUniqueRecord() {
        val export = HardwareLogExport()
        export.accept(header())
        export.accept(record(2))
        export.accept(record(1))
        assertFalse(export.complete)
        assertEquals(HardwareLogExport.Result.COMPLETE, export.accept(end()))
        assertTrue(export.complete)
        assertEquals(2, export.received)
        assertEquals(4, export.rawLines.size)
    }

    @Test fun finalIndexDoesNotHideMissingEarlierRecord() {
        val export = HardwareLogExport()
        export.accept(header()); export.accept(record(2))
        assertEquals(HardwareLogExport.Result.FAILED, export.accept(end()))
        assertEquals(1, export.received)
        assertFalse(export.complete)
    }

    @Test fun identicalDuplicatesAreDeduplicated() {
        val export = HardwareLogExport()
        export.accept(header()); export.accept(record(1))
        assertEquals(HardwareLogExport.Result.IGNORED, export.accept(record(1)))
        export.accept(record(2)); export.accept(end())
        assertTrue(export.complete)
        assertEquals(4, export.rawLines.size)
    }

    @Test fun conflictingDuplicateFails() {
        val export = HardwareLogExport()
        export.accept(header()); export.accept(record(1))
        assertEquals(HardwareLogExport.Result.FAILED, export.accept(record(1).replace("boot", "error")))
    }

    @Test fun oldExportPacketsCannotChangeCurrentSnapshot() {
        val export = HardwareLogExport(setOf(6L))
        assertEquals(HardwareLogExport.Result.IGNORED, export.accept(header(x = 6)))
        export.accept(header())
        assertEquals(HardwareLogExport.Result.IGNORED, export.accept(record(1, x = 6)))
        assertEquals(HardwareLogExport.Result.IGNORED, export.accept(end(x = 8)))
        assertEquals(0, export.received)
        assertFalse(export.finished)
    }

    @Test fun repeatedOrMissingGlobalSequenceFails() {
        val export = HardwareLogExport()
        export.accept(header()); export.accept(record(1))
        assertEquals(HardwareLogExport.Result.FAILED, export.accept(record(2, s = 10)))
    }

    @Test fun mismatchedCountOrEndCannotComplete() {
        val wrongCount = HardwareLogExport()
        wrongCount.accept(header())
        assertEquals(HardwareLogExport.Result.FAILED, wrongCount.accept(record(1, n = 3)))
        val wrongEnd = HardwareLogExport()
        wrongEnd.accept(header()); wrongEnd.accept(record(1)); wrongEnd.accept(record(2))
        assertEquals(HardwareLogExport.Result.FAILED, wrongEnd.accept(end(last = 12)))
    }

    @Test fun emptySnapshotIsCompleteWithBothMarkers() {
        val export = HardwareLogExport()
        export.accept(header(n = 0, last = 0))
        assertEquals(HardwareLogExport.Result.COMPLETE, export.accept(end(n = 0, last = 0)))
    }

    @Test fun missingHeaderOrMalformedMarkersFailClosed() {
        assertEquals(HardwareLogExport.Result.FAILED, HardwareLogExport().accept(record(1)))
        assertEquals(HardwareLogExport.Result.FAILED, HardwareLogExport().accept("ok hwlog_export n=2"))
        val export = HardwareLogExport()
        export.accept(header())
        assertEquals(HardwareLogExport.Result.FAILED, export.accept("hwlog i=1 n=2 s=10"))
    }

    @Test fun timeoutAndDisconnectRemainIncompleteEvenIfEndArrivesLate() {
        val export = HardwareLogExport()
        export.accept(header()); export.accept(record(1)); export.accept(record(2))
        export.fail("连接中断")
        assertEquals(HardwareLogExport.Result.IGNORED, export.accept(end()))
        assertFalse(export.complete)
        assertEquals(3, export.rawLines.size)
    }
}
