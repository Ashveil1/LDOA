package dev.phonelinux.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class XdiagTraceScannerTest {
    private fun window(setupId: String = "005"): String =
        "004:<: am lsb-first\n004:>: Success, version is 11:0\n" +
        "$setupId:<: am lsb-first\n$setupId:>: Success, version is 11:0\n" +
        "$setupId:<:1a2b: RANDR-Request(140,5): GetMonitors window=0x0 get_active=1\n"

    private fun feedAll(s: XdiagTraceScanner, text: String, chunk: Int) {
        val b = text.toByteArray(Charsets.ISO_8859_1)
        var i = 0
        while (i < b.size) { val n = minOf(chunk, b.size - i); s.feed(b, i, n); i += n }
    }

    @Test fun correlatedAcrossMidLineChunkSplits_agreesWithBatchPredicate() {
        val text = window()
        val s = XdiagTraceScanner(firstLineComplete = true)
        feedAll(s, text, 7) // deliberately tiny: splits lines mid-shape
        s.finish()
        assertNull(s.unknownReason())
        assertEquals(setOf("005"), s.correlated())
        // Agreement with the batch predicate on the same bytes.
        assertEquals(XdiagPolicy.correlatedGetMonitorsConnections(text), s.correlated())
        assertEquals(text.length.toLong(), s.scannedBytes)
    }

    @Test fun bareGetMonitorsWithoutInWindowSetupExcluded() {
        // run 41590351769 conn 008 shape: setup BEFORE the window, only
        // bare GetMonitors requests inside it.
        val text = "008:<:1a2b: RANDR-Request(140,5): GetMonitors window=0x0 get_active=1\n"
        val s = XdiagTraceScanner(firstLineComplete = true)
        feedAll(s, text, 64)
        s.finish()
        assertNull(s.unknownReason())
        assertEquals(emptySet<String>(), s.correlated())
    }

    @Test fun leadingFragmentDiscardedAndCounted() {
        val frag = "0x01,0x00,0,0xec," // tail of a line cut by base
        val rest = "\n" + window("007")
        val s = XdiagTraceScanner(firstLineComplete = false)
        feedAll(s, frag + rest, 4096)
        s.finish()
        assertNull(s.unknownReason())
        assertEquals(frag.length.toLong(), s.discardedFragmentBytes)
        assertEquals(setOf("007"), s.correlated())
    }

    @Test fun boundaryLineIsScannedWhenComplete() {
        val s = XdiagTraceScanner(firstLineComplete = true)
        feedAll(s, window("009"), 4096)
        s.finish()
        assertNull(s.unknownReason())
        assertEquals(setOf("009"), s.correlated())
        assertEquals(0L, s.discardedFragmentBytes)
    }

    @Test fun carryOverflowFailsClosed() {
        val s = XdiagTraceScanner(firstLineComplete = true)
        val big = ByteArray(XdiagPolicy.SCAN_CHUNK_BYTES) { 'x'.code.toByte() }
        repeat(XdiagPolicy.SCAN_CARRY_CAP / XdiagPolicy.SCAN_CHUNK_BYTES) { s.feed(big) }
        assertNull(s.unknownReason()) // exactly AT the cap is still legal
        s.feed(big)
        assertTrue(s.unknownReason()!!.contains("carry overflow"))
        // frozen: later feeds are ignored
        s.feed(window("001").toByteArray(Charsets.ISO_8859_1))
        assertEquals(emptySet<String>(), s.correlated())
    }

    @Test fun connectionOverflowFailsClosed() {
        val s = XdiagTraceScanner(firstLineComplete = true)
        val text = (1..65).joinToString("") { "%03d:<: am lsb-first\n".format(it) }
        feedAll(s, text, 4096)
        assertTrue(s.unknownReason()!!.contains("connection state overflow"))
    }

    @Test fun sameIdReuseFailsClosed() {
        val s = XdiagTraceScanner(firstLineComplete = true)
        feedAll(s, "005:<: am lsb-first\n005:<: am lsb-first\n", 4096)
        assertTrue(s.unknownReason()!!.contains("same-id reuse"))
    }

    @Test fun terminalPartialWithTracedPrefixIsUnknown() {
        val s = XdiagTraceScanner(firstLineComplete = true)
        feedAll(s, window("005") + "010:<: am lsb", 4096) // no trailing newline
        s.finish()
        assertTrue(s.unknownReason()!!.contains("mid-line"))
    }

    @Test fun terminalContinuationLineExcludedCleanly() {
        val s = XdiagTraceScanner(firstLineComplete = true)
        feedAll(s, window("005") + "          data=0x00,0x01", 4096) // indented continuation
        s.finish()
        assertNull(s.unknownReason())
        assertEquals(setOf("005"), s.correlated())
    }

    @Test fun utf8SplitAcrossChunksIsByteExact() {
        // A multi-byte UTF-8 char split across chunks inside a line that
        // also carries a shape: ISO-8859-1 decode keeps matching byte-exact.
        val text = "005:<: am lsb-first \u00e9\n005:>: Success, version is 11:0\n" +
            "005:<:1a2b: RANDR-Request(140,5): GetMonitors window=0x0 get_active=1\n"
        val b = text.toByteArray(Charsets.UTF_8)
        val splitAt = text.indexOf('\u00e9').let { idx ->
            text.substring(0, idx).toByteArray(Charsets.UTF_8).size + 1 } // inside the 2-byte char
        val s = XdiagTraceScanner(firstLineComplete = true)
        s.feed(b, 0, splitAt)
        s.feed(b, splitAt, b.size - splitAt)
        s.finish()
        assertNull(s.unknownReason())
        assertEquals(setOf("005"), s.correlated())
        assertEquals(b.size.toLong(), s.scannedBytes)
    }

    @Test fun oversizeChunkViolatesCallerContract() {
        val s = XdiagTraceScanner(firstLineComplete = true)
        try {
            s.feed(ByteArray(XdiagPolicy.SCAN_CHUNK_BYTES + 1))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) { /* contract */ }
    }

    @Test fun wideIdsMatchAboveThreeDigits() {
        val text = "1000:<: am lsb-first\n1000:>: Success, version is 11:0\n" +
            "1000:<:1a2b: RANDR-Request(140,5): GetMonitors window=0x0 get_active=1\n"
        val s = XdiagTraceScanner(firstLineComplete = true)
        feedAll(s, text, 3)
        s.finish()
        assertNull(s.unknownReason())
        assertEquals(setOf("1000"), s.correlated())
    }

    @Test fun capExactlyThenNewlineIsLegal() {
        // carry reaches exactly the cap via no-newline chunks, then an
        // empty segment + newline completes an AT-cap line: legal.
        val s = XdiagTraceScanner(firstLineComplete = true)
        val big = ByteArray(XdiagPolicy.SCAN_CHUNK_BYTES) { 'x'.code.toByte() }
        repeat(XdiagPolicy.SCAN_CARRY_CAP / XdiagPolicy.SCAN_CHUNK_BYTES) { s.feed(big) }
        assertNull(s.unknownReason())
        s.feed(byteArrayOf('\n'.code.toByte()))
        assertNull(s.unknownReason())
    }

    @Test fun capPlusOneThenNewlineFailsClosed() {
        val s = XdiagTraceScanner(firstLineComplete = true)
        val big = ByteArray(XdiagPolicy.SCAN_CHUNK_BYTES) { 'x'.code.toByte() }
        repeat(XdiagPolicy.SCAN_CARRY_CAP / XdiagPolicy.SCAN_CHUNK_BYTES) { s.feed(big) }
        s.feed(byteArrayOf('y'.code.toByte(), '\n'.code.toByte()))
        assertTrue(s.unknownReason()!!.contains("completed line"))
    }

    @Test fun leadingFragmentOverCapFailsClosedBeforeDiscard() {
        val s = XdiagTraceScanner(firstLineComplete = false)
        val big = ByteArray(XdiagPolicy.SCAN_CHUNK_BYTES) { 'x'.code.toByte() }
        repeat(XdiagPolicy.SCAN_CARRY_CAP / XdiagPolicy.SCAN_CHUNK_BYTES) { s.feed(big) }
        s.feed(byteArrayOf('y'.code.toByte(), '\n'.code.toByte()))
        assertTrue(s.unknownReason()!!.contains("carry overflow"))
    }

    @Test fun terminalPartialAmbiguity_shortPrefixes() {
        // "010" / "010:<" can still grow into a traced shape line.
        for (frag in listOf("010", "010:<", "010:<: am lsb-fir")) {
            val s = XdiagTraceScanner(firstLineComplete = true)
            feedAll(s, window("005") + frag, 4096)
            assertTrue("frag=" + frag, s.terminalPartialAmbiguous())
            s.finish()
            assertTrue(s.unknownReason()!!.contains("mid-line"))
        }
        // continuation-style partial (space-led) is NOT ambiguous
        val s2 = XdiagTraceScanner(firstLineComplete = true)
        feedAll(s2, window("005") + "  partial-data", 4096)
        assertTrue(!s2.terminalPartialAmbiguous())
        s2.finish()
        assertNull(s2.unknownReason())
        assertEquals(setOf("005"), s2.correlated())
    }

    private class FakeReader(val data: ByteArray) {
        var pos = 0
        var zeroProgress = false
        fun read(buf: ByteArray, max: Int): Int {
            if (zeroProgress) return 0
            if (pos >= data.size) return -1
            val n = minOf(max, data.size - pos)
            System.arraycopy(data, pos, buf, 0, n)
            pos += n
            return n
        }
    }

    @Test fun feedRegionDone_consumesExactRangeInOrder() {
        val text = window("005") +
            "006:<: am lsb-first\n006:>: Success, version is 11:0\n" +
            "006:<:1a2b: RANDR-Request(140,5): GetMonitors window=0x0 get_active=1\n"
        val data = text.toByteArray(Charsets.ISO_8859_1)
        val r = FakeReader(data)
        val s = XdiagTraceScanner(firstLineComplete = true)
        val fr = feedRegion(s, data.size.toLong(), System.nanoTime() + 60_000_000_000L) { b, m -> r.read(b, m) }
        assertEquals(RegionFeedResult.DONE, fr.result)
        assertEquals(data.size.toLong(), fr.bytesFed)
        s.finish()
        assertNull(s.unknownReason())
        assertEquals(setOf("005", "006"), s.correlated())
    }

    @Test fun feedRegionShortReadIsUnknown_neverSkipped() {
        val data = window().toByteArray(Charsets.ISO_8859_1)
        val r = FakeReader(data)
        val s = XdiagTraceScanner(firstLineComplete = true)
        val fr = feedRegion(s, data.size + 100L, System.nanoTime() + 60_000_000_000L) { b, m -> r.read(b, m) }
        assertEquals(RegionFeedResult.SHORT_READ, fr.result)
        assertEquals(data.size.toLong(), fr.bytesFed) // exactly what existed
    }

    @Test fun feedRegionNoProgressStops() {
        val r = FakeReader(ByteArray(10))
        r.zeroProgress = true
        val s = XdiagTraceScanner(firstLineComplete = true)
        val fr = feedRegion(s, 10, System.nanoTime() + 60_000_000_000L) { b, m -> r.read(b, m) }
        assertEquals(RegionFeedResult.NO_PROGRESS, fr.result)
        assertEquals(0L, fr.bytesFed)
    }

    @Test fun feedRegionScannerUnknownStopsPromptly() {
        val bad = "005:<: am lsb-first\n005:<: am lsb-first\n"
        val data = (bad + "x".repeat(1_000_000)).toByteArray(Charsets.ISO_8859_1)
        val r = FakeReader(data)
        val s = XdiagTraceScanner(firstLineComplete = true)
        val fr = feedRegion(s, data.size.toLong(), System.nanoTime() + 60_000_000_000L) { b, m -> r.read(b, m) }
        assertEquals(RegionFeedResult.SCANNER_UNKNOWN, fr.result)
        assertTrue("fed=" + fr.bytesFed, fr.bytesFed < data.size) // stopped early
        assertTrue(s.unknownReason()!!.contains("same-id reuse"))
    }

    @Test fun feedRegionDeadlineBeforeFirstChunk() {
        val r = FakeReader(window().toByteArray(Charsets.ISO_8859_1))
        val s = XdiagTraceScanner(firstLineComplete = true)
        val past = System.nanoTime() - 1_000
        val fr = feedRegion(s, 100, past) { b, m -> r.read(b, m) }
        assertEquals(RegionFeedResult.DEADLINE, fr.result)
        assertEquals(0L, fr.bytesFed)
    }

    @Test fun feedRegionPreUnknownMakesZeroReaderCalls() {
        val s = XdiagTraceScanner(firstLineComplete = true)
        feedAll(s, "005:<: am lsb-first\n005:<: am lsb-first\n", 4096)
        assertTrue(s.unknownReason() != null) // already UNKNOWN before the region
        var readerCalls = 0
        val fr = feedRegion(s, 4096, System.nanoTime() + 60_000_000_000L) { _, _ ->
            readerCalls++; fail("reader must not be called when scanner is already UNKNOWN"); 0 }
        assertEquals(RegionFeedResult.SCANNER_UNKNOWN, fr.result)
        assertEquals(0L, fr.bytesFed)
        assertEquals(0, readerCalls)
    }

    @Test fun feedRegionDeadlineMidRegion_stopsBetweenChunks() {
        val chunk = XdiagPolicy.SCAN_CHUNK_BYTES
        val data = ByteArray(chunk * 3) { 'x'.code.toByte() } // 3 full chunks
        val r = FakeReader(data)
        val s = XdiagTraceScanner(firstLineComplete = true)
        var clockCalls = 0
        val t0 = System.nanoTime()
        // expire exactly before the 3rd chunk read (checks are pre-read)
        val fr = feedRegion(s, data.size.toLong(), t0 + 1_000,
            nowNs = { clockCalls++; if (clockCalls <= 2) t0 else t0 + 2_000 }) { b, m -> r.read(b, m) }
        assertEquals(RegionFeedResult.DEADLINE, fr.result)
        assertEquals((chunk * 2).toLong(), fr.bytesFed)
    }

    @Test fun feedRegionDeadlineOnFinalRead() {
        val chunk = XdiagPolicy.SCAN_CHUNK_BYTES
        val data = ByteArray(chunk * 2) { 'x'.code.toByte() } // exactly 2 chunks
        val r = FakeReader(data)
        val s = XdiagTraceScanner(firstLineComplete = true)
        var clockCalls = 0
        val t0 = System.nanoTime()
        // expire right before the FINAL (2nd) chunk read
        val fr = feedRegion(s, data.size.toLong(), t0 + 1_000,
            nowNs = { clockCalls++; if (clockCalls <= 1) t0 else t0 + 2_000 }) { b, m -> r.read(b, m) }
        assertEquals(RegionFeedResult.DEADLINE, fr.result)
        assertEquals(chunk.toLong(), fr.bytesFed) // first chunk fed, final never read
    }

    @Test fun feedRegionDoneWhenClockCrossesDuringFinalRead_decisionDeniesPass() {
        // The clock crosses DURING the final read: feedRegion's pre-read
        // check saw a live deadline, so the region completes DONE. The
        // PASS denial then lives in the shared pure decision, tested here
        // exactly as MDET calls it.
        val chunk = XdiagPolicy.SCAN_CHUNK_BYTES
        val data = ByteArray(chunk * 2) { 'x'.code.toByte() }
        val r = FakeReader(data)
        val s = XdiagTraceScanner(firstLineComplete = true)
        val t0 = System.nanoTime()
        var now = t0
        val fr = feedRegion(s, data.size.toLong(), t0 + 1_000, nowNs = { now }) { b, m ->
            val n = r.read(b, m)
            if (r.pos >= data.size) now = t0 + 2_000 // crossed during final read
            n
        }
        assertEquals(RegionFeedResult.DONE, fr.result)
        assertEquals(data.size.toLong(), fr.bytesFed)
        // ...but the decision with the post-read clock must deny PASS.
        assertTrue(!XdiagPolicy.smokePassAllowed(setOf("005"), true, false, now, t0 + 1_000))
    }
}
