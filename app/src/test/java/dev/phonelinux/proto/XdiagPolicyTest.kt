package dev.phonelinux.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XdiagPolicyTest {
    @Test fun canonicalDisplay_acceptsOnlyCanonicalNonZero() {
        assertTrue(XServer.canonicalTestDisplayForTest(":10"))
        assertTrue(XServer.canonicalTestDisplayForTest(":1"))
        assertTrue(XServer.canonicalTestDisplayForTest(":999"))
        assertFalse(XServer.canonicalTestDisplayForTest(":0"))
        assertFalse(XServer.canonicalTestDisplayForTest(":00"))
        assertFalse(XServer.canonicalTestDisplayForTest(":01"))
        assertFalse(XServer.canonicalTestDisplayForTest(":1000"))
        assertFalse(XServer.canonicalTestDisplayForTest(""))
        assertFalse(XServer.canonicalTestDisplayForTest("10"))
        assertFalse(XServer.canonicalTestDisplayForTest(":1a"))
        assertFalse(XServer.canonicalTestDisplayForTest(":-1"))
    }

    @Test fun overallBudget_coversStartupObserveAndMargin() {
        val start = 900_000L
        assertEquals(start + 40_000L + 240_000L, XdiagPolicy.totalBudgetMs(start))
    }

    @Test fun proxyLifetime_coversDeadlinePlusCleanup_neverSilentlyLonger() {
        val total = XdiagPolicy.totalBudgetMs(900_000L)
        val sec = XdiagPolicy.proxyLifetimeSec(total)
        assertTrue(sec * 1000L >= total + XdiagPolicy.CLEANUP_MARGIN_MS)
        assertTrue(sec * 1000L <= total + XdiagPolicy.CLEANUP_MARGIN_MS + 1000L)
    }

    @Test fun traceCap_blocksAre512Units() {
        assertEquals(XdiagPolicy.TRACE_FILE_CAP_BYTES / 512, XdiagPolicy.traceCapBlocks512())
    }

    @Test fun exportPlan_fittingFileIsFull() {
        assertEquals(XdiagPolicy.ExportPlan.Full(100), XdiagPolicy.exportPlan(100, 100, 40))
        assertEquals(XdiagPolicy.ExportPlan.Full(99), XdiagPolicy.exportPlan(99, 100, 40))
    }

    @Test fun exportPlan_truncatedExactlyFillsRemaining() {
        val p = XdiagPolicy.exportPlan(10_000, 900, 40)
        assertTrue(p is XdiagPolicy.ExportPlan.Truncated)
        p as XdiagPolicy.ExportPlan.Truncated
        assertEquals(40, p.markerLen)
        assertEquals(900L, p.head + p.markerLen + p.tail.toLong())
        assertTrue(p.head > 0 && p.tail > 0)
    }

    @Test fun exportPlan_markerOnlyWhenNoRoomForContent() {
        // remaining 50, marker 40: only 10 bytes left, head+tail need >= 2
        // -> still truncatable (head=5, tail=5); remaining == markerLen
        // exactly -> marker-only Truncated(0, m, 0).
        val markerOnly = XdiagPolicy.exportPlan(10_000, 40, 40)
        assertEquals(XdiagPolicy.ExportPlan.Truncated(0, 40, 0), markerOnly)
    }

    @Test fun exportPlan_omitsWhenEvenMarkerDoesNotFit() {
        assertEquals(XdiagPolicy.ExportPlan.Omit, XdiagPolicy.exportPlan(10_000, 39, 40))
        assertEquals(XdiagPolicy.ExportPlan.Omit, XdiagPolicy.exportPlan(10_000, 0, 40))
    }

    @Test fun wmClass_strictTwoFieldParser() {
        assertTrue(XdiagPolicy.wmClassIsPcmanfm("WM_CLASS(STRING) = \"pcmanfm\", \"pcmanfm\""))
        assertTrue(XdiagPolicy.wmClassIsPcmanfm("WM_CLASS(STRING) = \"Pcmanfm\", \"PCMANFM\""))
        // other class
        assertFalse(XdiagPolicy.wmClassIsPcmanfm("WM_CLASS(STRING) = \"xterm\", \"XTerm\""))
        // contains-spoof attempts must NOT match
        assertFalse(XdiagPolicy.wmClassIsPcmanfm("XX_WM_CLASS(STRING) = \"pcmanfm\", \"pcmanfm\""))
        assertFalse(XdiagPolicy.wmClassIsPcmanfm("WM_CLASS(STRING) = \"pcmanfm\", \"pcmanfm\" EXTRA"))
        assertFalse(XdiagPolicy.wmClassIsPcmanfm("WM_CLASS(STRING) = \"notpcmanfm\", \"pcmanfm2\""))
        assertFalse(XdiagPolicy.wmClassIsPcmanfm("no wm class here"))
        // multiline xprop output: the matching line can sit among others
        assertTrue(XdiagPolicy.wmClassIsPcmanfm(
            "_NET_WM_PID(CARDINAL) = 4242\nWM_CLASS(STRING) = \"pcmanfm\", \"pcmanfm\"\n"))
    }

    @Test fun correlatedGetMonitors_groundedFixtureLines() {
        // Verbatim line prefixes from the actual healthy-session capture
        // (/tmp/xrepro/out3/xtrace.log, connection 001: setup=1,
        // success=1, GetMonitors=7 in the real log).
        val window = """
001:<: am lsb-first want 11:0 authorising with '' of length 0
001:>: Success, version is 11:0 vendor='The X.Org Foundation' release=12101004
001:<:002d: 12: RANDR-Request(140,42): GetMonitors opcode=0x8c opcode2=0x2a
002:<: am lsb-first want 11:0 authorising with '' of length 0
002:>: Success, version is 11:0 vendor='The X.Org Foundation' release=12101004
002:<:0011:  2: RANDR-Request(140,42): GetScreenResources opcode=0x8c
""".trimIndent()
        // 001 carries all three shapes; 002 lacks GetMonitors -> excluded
        assertEquals(setOf("001"), XdiagPolicy.correlatedGetMonitorsConnections(window))
        // %03d is MINIMUM width (source-verified): ids >= 1000 print wider
        val wide = window.replace("001:", "1000:")
        assertEquals(setOf("1000"), XdiagPolicy.correlatedGetMonitorsConnections(wide))
        // GetMonitors without a setup handshake in-window -> not fresh
        assertEquals(emptySet<String>(), XdiagPolicy.correlatedGetMonitorsConnections(
            "003:<:0009:  7: RANDR-Request(140,42): GetMonitors opcode=0x8c"))
        assertEquals(emptySet<String>(), XdiagPolicy.correlatedGetMonitorsConnections(""))
    }

    @Test fun scanDecision_fullTable() {
        // foreign UID is skipped regardless of readability/existence
        assertEquals(XdiagPolicy.ScanDecision.SKIP_FOREIGN, XdiagPolicy.scanDecision(false, true, true))
        assertEquals(XdiagPolicy.ScanDecision.SKIP_FOREIGN, XdiagPolicy.scanDecision(false, false, true))
        assertEquals(XdiagPolicy.ScanDecision.SKIP_FOREIGN, XdiagPolicy.scanDecision(false, true, false))
        assertEquals(XdiagPolicy.ScanDecision.SKIP_FOREIGN, XdiagPolicy.scanDecision(false, false, false))
        // own + readable = usable
        assertEquals(XdiagPolicy.ScanDecision.USE, XdiagPolicy.scanDecision(true, true, true))
        assertEquals(XdiagPolicy.ScanDecision.USE, XdiagPolicy.scanDecision(true, true, false))
        // own + vanished = GONE (never conflated with unreadable)
        assertEquals(XdiagPolicy.ScanDecision.GONE, XdiagPolicy.scanDecision(true, false, false))
        // own + unreadable while still existing = UNKNOWN (fail closed)
        assertEquals(XdiagPolicy.ScanDecision.UNKNOWN, XdiagPolicy.scanDecision(true, false, true))
    }

    @Test fun aggregateCleanup_nullWhenNothingToThrow() {
        assertNull(XdiagPolicy.aggregateCleanup(null, emptyList()))
    }

    @Test fun aggregateCleanup_primaryOnlyIsReturnedUnwrapped() {
        val p = AssertionError("primary")
        val out = XdiagPolicy.aggregateCleanup(p, emptyList())
        assertTrue(out === p)
        assertEquals(0, out!!.suppressed.size)
    }

    @Test fun aggregateCleanup_allFailuresSuppressedOnPrimaryInLadderOrder() {
        val p = AssertionError("primary")
        val f1 = AssertionError("stop failed")
        val f2 = AssertionError("export INCOMPLETE")
        val f3 = AssertionError("release failed")
        val out = XdiagPolicy.aggregateCleanup(p, listOf(f1, f2, f3))!!
        assertTrue(out === p)
        assertEquals(3, out.suppressed.size)
        assertTrue(out.suppressed[0] === f1)
        assertTrue(out.suppressed[1] === f2)
        assertTrue(out.suppressed[2] === f3)
    }

    @Test fun aggregateCleanup_noPrimaryWrapsAllWithCount() {
        val f1 = AssertionError("stop failed")
        val f2 = AssertionError("export INCOMPLETE")
        val out = XdiagPolicy.aggregateCleanup(null, listOf(f1, f2))!!
        assertTrue(out is AssertionError)
        assertTrue(out.message!!.contains("2 failure(s)"))
        assertEquals(2, out.suppressed.size)
        assertTrue(out.suppressed[0] === f1)
        assertTrue(out.suppressed[1] === f2)
    }

    @Test fun aggregateCleanup_run41590351769_exportVerdictNoLongerSwallowed() {
        // Regression for the observed swallow: primary (trace-cap guard)
        // plus TWO cleanup failures; the first-wins ladder kept only the
        // proxy-stop failure and silently lost the export's INCOMPLETE
        // verdict. Both must survive as suppressed on the primary.
        val primary = AssertionError("xdiag: trace window 588203 bytes exceeds read cap - UNKNOWN")
        val stop = AssertionError("proxy stop failed")
        val export = AssertionError("xdiag: export INCOMPLETE (required artifact(s) missing) - diagnostic UNUSABLE")
        val out = XdiagPolicy.aggregateCleanup(primary, listOf(stop, export))!!
        assertTrue(out === primary)
        assertEquals(2, out.suppressed.size)
        assertTrue(out.suppressed[0] === stop)
        assertTrue(out.suppressed[1] === export)
    }

    @Test fun aggregateCleanup_selfSuppressionNeverMasksOriginal() {
        // If the primary itself lands in the failures list, addSuppressed
        // would throw IAE and MASK the original. The guard must skip it.
        val p = AssertionError("primary")
        val f = AssertionError("stop failed")
        val out = XdiagPolicy.aggregateCleanup(p, listOf(p, f, p))!!
        assertTrue(out === p)
        assertEquals(1, out.suppressed.size)
        assertTrue(out.suppressed[0] === f)
    }

    @Test fun aggregateCleanup_duplicateNonPrimaryFailuresBothRecorded() {
        // Two occurrences of the SAME cleanup failure instance are distinct
        // ladder events; addSuppressed rejects only self-suppression, so both
        // must be recorded.
        val p = AssertionError("primary")
        val f = AssertionError("stop failed")
        val out = XdiagPolicy.aggregateCleanup(p, listOf(f, f))!!
        assertEquals(2, out.suppressed.size)
        assertTrue(out.suppressed[0] === f && out.suppressed[1] === f)
    }

    @Test fun scannerBounds_chunkNeverExceedsReadCap_budgetIndependentOfUlimit() {
        assertTrue(XdiagPolicy.SCAN_CHUNK_BYTES <= XdiagPolicy.TRACE_READ_CAP)
        assertEquals(XdiagPolicy.TRACE_FILE_CAP_BYTES + XdiagPolicy.SCAN_CHUNK_BYTES,
            XdiagPolicy.SCAN_TOTAL_BUDGET_BYTES)
        assertTrue(XdiagPolicy.SCAN_CARRY_CAP <= XdiagPolicy.TRACE_READ_CAP)
    }

    @Test fun exportLayoutReserve_markerFitsAndCapStaysLiteral() {
        assertTrue(XdiagPolicy.EXPORT_LAYOUT_MARKER.toByteArray(Charsets.UTF_8).size
            <= XdiagPolicy.EXPORT_LAYOUT_RESERVE_BYTES)
        assertEquals(XdiagPolicy.EXPORT_CAP_BYTES,
            XdiagPolicy.payloadCapBytes() + XdiagPolicy.EXPORT_LAYOUT_RESERVE_BYTES)
    }

    @Test fun smokePassAllowed_deadlineNeverPermitsPass() {
        val now = 1_000_000L; val dl = 2_000_000L
        // clean pass
        assertTrue(XdiagPolicy.smokePassAllowed(setOf("010"), true, false, now, dl))
        // expired deadline denies, even with clean correlation
        assertTrue(!XdiagPolicy.smokePassAllowed(setOf("010"), true, false, dl, dl))
        assertTrue(!XdiagPolicy.smokePassAllowed(setOf("010"), true, false, dl + 1, dl))
        // other denials
        assertTrue(!XdiagPolicy.smokePassAllowed(emptySet(), true, false, now, dl))
        assertTrue(!XdiagPolicy.smokePassAllowed(setOf("010"), false, false, now, dl))
        assertTrue(!XdiagPolicy.smokePassAllowed(setOf("010"), true, true, now, dl))
    }
}
