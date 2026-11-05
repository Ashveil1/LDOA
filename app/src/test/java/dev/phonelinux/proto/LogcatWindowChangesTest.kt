package dev.phonelinux.proto

import org.junit.Assert.*
import org.junit.Test

class LogcatWindowChangesTest {
    private val id = "0123456789abcdef"
    private val ts = "11-05 20:00:0"

    private fun line(tag: String, msg: String, prio: Char = 'E', n: Int = 1) =
        "$ts$n.000 1000 2000 $prio $tag: $msg"

    private fun begin(case: String = "patched") = line("tx11-request", LogcatWindowChanges.markerLine(true, case, id), n = 1)
    private fun end(case: String = "patched") = line("tx11-request", LogcatWindowChanges.markerLine(false, case, id), n = 9)
    private fun req(w: Int, h: Int, n: Int) = line("tx11-request", "window changed: $w $h builtin", n = n)
    private fun apply(n: Int) = line("LorieNative", "New reported framerate is 60", prio = 'V', n = n)

    @Test fun scopedWindow_classifiesOrderedRequestsAndApplies() {
        val cap = listOf(begin(), req(896, 1570, 2), apply(3), req(1080, 1949, 4), apply(5), end()).joinToString("\n")
        val r = LogcatWindowChanges.parse(cap, "patched", id, truncated = false, beginSent = true, endSent = true)
        assertEquals(LogcatWindowChanges.Status.OK, r.status)
        assertEquals(4, r.events.size)
        assertEquals(LogcatWindowChanges.Kind.REQUEST, r.events[0].kind)
        assertEquals(896, r.events[0].width); assertEquals(1570, r.events[0].height)
        assertEquals(LogcatWindowChanges.Kind.APPLY, r.events[1].kind)
        assertEquals(1080, r.events[2].width); assertEquals(1949, r.events[2].height)
        assertEquals(0, r.staleDropped)
    }

    @Test fun intermediateRequest_isPreservedInOrder_noDedup() {
        val cap = listOf(begin(), req(1800, 414, 2), req(1800, 1949, 3), req(1080, 1949, 4), end()).joinToString("\n")
        val r = LogcatWindowChanges.parse(cap, "patched", id, false, true, true)
        assertEquals(LogcatWindowChanges.Status.OK, r.status)
        assertEquals(listOf(1800 to 414, 1800 to 1949, 1080 to 1949), r.events.map { it.width to it.height })
    }


    // Actual returned lines from run 42034582889 (patched case, marker id
    // c0f3a9725021c288): the transport wrapped markers in ONE quote pair.
    private val runId42034582889 = "c0f3a9725021c288"
    private val actualBegin =
        "11-05 13:41:14.421  5990  5990 E tx11-request: 'LDOA-MANAGED-CASE MARKER BEGIN case=patched id=c0f3a9725021c288'"
    private val actualEnd =
        "11-05 13:41:55.975  6718  6718 E tx11-request: 'LDOA-MANAGED-CASE MARKER END case=patched id=c0f3a9725021c288'"
    private val actualRequest =
        "11-05 13:41:44.600  5896  5926 E tx11-request: window changed: 1080 1929 builtin"

    private fun qline(markerBegin: Boolean, case: String = "patched", markerId: String = id,
                      open: String = "'", close: String = "'") =
        line("tx11-request", open + LogcatWindowChanges.markerLine(markerBegin, case, markerId) + close)

    @Test fun quotedActualRunFixture_scopesAndClassifies() {
        val cap = listOf(actualBegin, actualRequest, actualEnd).joinToString("\n")
        val r = LogcatWindowChanges.parse(cap, "patched", runId42034582889, false, true, true)
        assertEquals(LogcatWindowChanges.Status.OK, r.status)
        assertEquals(1, r.events.size)
        assertEquals(LogcatWindowChanges.Kind.REQUEST, r.events[0].kind)
        assertEquals(1080, r.events[0].width); assertEquals(1929, r.events[0].height)
    }

    @Test fun bareAndQuotedMarkers_bothPass() {
        val bare = listOf(begin(), req(1080, 1949, 2), end()).joinToString("\n")
        assertEquals(LogcatWindowChanges.Status.OK,
            LogcatWindowChanges.parse(bare, "patched", id, false, true, true).status)
        val quoted = listOf(qline(true), req(1080, 1949, 2), qline(false)).joinToString("\n")
        assertEquals(LogcatWindowChanges.Status.OK,
            LogcatWindowChanges.parse(quoted, "patched", id, false, true, true).status)
    }

    @Test fun mismatchedQuotes_strictReject() {
        val openOnly = listOf(qline(true, close = ""), req(1080, 1949, 2), qline(false)).joinToString("\n")
        assertEquals(LogcatWindowChanges.Status.UNAVAILABLE,
            LogcatWindowChanges.parse(openOnly, "patched", id, false, true, true).status)
        val closeOnly = listOf(qline(true, open = ""), req(1080, 1949, 2), qline(false)).joinToString("\n")
        assertEquals(LogcatWindowChanges.Status.UNAVAILABLE,
            LogcatWindowChanges.parse(closeOnly, "patched", id, false, true, true).status)
    }

    @Test fun nestedQuotes_strictReject() {
        val nested = listOf(qline(true, open = "''", close = "''"), req(1080, 1949, 2), qline(false)).joinToString("\n")
        assertEquals(LogcatWindowChanges.Status.UNAVAILABLE,
            LogcatWindowChanges.parse(nested, "patched", id, false, true, true).status)
    }

    @Test fun quotedWrongCaseOrId_strictReject() {
        val wrongCase = listOf(qline(true, case = "original"), req(1080, 1949, 2), qline(false)).joinToString("\n")
        assertEquals(LogcatWindowChanges.Status.UNAVAILABLE,
            LogcatWindowChanges.parse(wrongCase, "patched", id, false, true, true).status)
        val wrongId = listOf(qline(true, markerId = "ffffffffffffffff"), req(1080, 1949, 2), qline(false)).joinToString("\n")
        assertEquals(LogcatWindowChanges.Status.UNAVAILABLE,
            LogcatWindowChanges.parse(wrongId, "patched", id, false, true, true).status)
    }

    @Test fun prefixSuffixAroundQuotes_strictReject() {
        val pre = line("tx11-request", "x'" + LogcatWindowChanges.markerLine(true, "patched", id) + "'")
        val suf = line("tx11-request", "'" + LogcatWindowChanges.markerLine(true, "patched", id) + "'x")
        for (bad in listOf(pre, suf)) {
            val cap = listOf(bad, req(1080, 1949, 2), qline(false)).joinToString("\n")
            assertEquals(LogcatWindowChanges.Status.UNAVAILABLE,
                LogcatWindowChanges.parse(cap, "patched", id, false, true, true).status)
        }
    }

    @Test fun missingBegin_unavailable_neverInterpreted() {
        val cap = listOf(req(1080, 1949, 2), end()).joinToString("\n")
        val r = LogcatWindowChanges.parse(cap, "patched", id, false, true, true)
        assertEquals(LogcatWindowChanges.Status.UNAVAILABLE, r.status)
        assertTrue(r.reason.contains("BEGIN"))
        assertTrue(r.events.isEmpty())
    }

    @Test fun missingEnd_unavailable_windowIncomplete() {
        val cap = listOf(begin(), req(1080, 1949, 2)).joinToString("\n")
        val r = LogcatWindowChanges.parse(cap, "patched", id, false, true, true)
        assertEquals(LogcatWindowChanges.Status.UNAVAILABLE, r.status)
        assertTrue(r.reason.contains("END"))
    }

    @Test fun staleEntriesBeforeLastBegin_droppedAndCounted() {
        val old = line("tx11-request", LogcatWindowChanges.markerLine(true, "patched", id), n = 0)
        val cap = listOf(old, req(800, 1382, 0), begin(), req(1080, 1949, 2), end()).joinToString("\n")
        val r = LogcatWindowChanges.parse(cap, "patched", id, false, true, true)
        assertEquals(LogcatWindowChanges.Status.OK, r.status)
        assertEquals(2, r.staleDropped)
        assertEquals(1, r.events.size)
        assertEquals(1080, r.events[0].width)
    }

    @Test fun wrongCaseOrIdMarkers_doNotScope() {
        val cap = listOf(begin("original"), req(1080, 1949, 2), end("original")).joinToString("\n")
        val r = LogcatWindowChanges.parse(cap, "patched", id, false, true, true)
        assertEquals(LogcatWindowChanges.Status.UNAVAILABLE, r.status)
    }

    @Test fun unparseableLinesInsideWindow_keptRawAsOther() {
        val garbage = "this is not a threadtime line"
        val cap = listOf(begin(), req(1080, 1949, 2), garbage, end()).joinToString("\n")
        val r = LogcatWindowChanges.parse(cap, "patched", id, false, true, true)
        assertEquals(LogcatWindowChanges.Status.OK, r.status)
        assertEquals(2, r.events.size)
        assertEquals(LogcatWindowChanges.Kind.OTHER, r.events[1].kind)
        assertEquals(garbage, r.events[1].raw)
    }

    @Test fun nullCapture_unavailable() {
        val r = LogcatWindowChanges.parse(null, "patched", id, false, true, true)
        assertEquals(LogcatWindowChanges.Status.UNAVAILABLE, r.status)
        assertTrue(r.reason.contains("dump"))
    }

    @Test fun markerEmissionFailure_unavailableEvenWithPresentMarkers() {
        val cap = listOf(begin(), end()).joinToString("\n")
        val r = LogcatWindowChanges.parse(cap, "patched", id, false, beginSent = false, endSent = true)
        assertEquals(LogcatWindowChanges.Status.UNAVAILABLE, r.status)
        assertTrue(r.reason.contains("BEGIN marker emission"))
    }

    @Test fun truncatedFlag_isCarriedNotSilenced() {
        val cap = listOf(begin(), req(1080, 1949, 2), end()).joinToString("\n")
        val r = LogcatWindowChanges.parse(cap, "patched", id, truncated = true, beginSent = true, endSent = true)
        assertEquals(LogcatWindowChanges.Status.OK, r.status)
        assertTrue(r.truncated)
    }

    @Test fun cap_keepsRecentTailAndMarksTruncation() {
        val many = (1..3000).map { line("LorieNative", "New reported framerate is 60", n = it % 10) }
        val (text, truncated) = LogcatWindowChanges.cap(many.joinToString("\n"))
        assertTrue(truncated)
        assertTrue(text.lines().size <= LogcatWindowChanges.MAX_LINES)
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= LogcatWindowChanges.MAX_BYTES)
    }

    @Test fun cap_isByteAccurate_notCharCount() {
        // 300 lines x 100 euro signs: ~30k CHARS (under any char cap) but
        // ~90k BYTES - truncation must trigger on BYTES.
        val many = (1..300).map { "€".repeat(100) }
        val (text, truncated) = LogcatWindowChanges.cap(many.joinToString("\n"))
        assertTrue(truncated)
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= LogcatWindowChanges.MAX_BYTES)
        assertTrue(text.isNotEmpty())
    }

    @Test fun cap_shortInputUntouched() {
        val s = "a\nb"
        assertEquals(s to false, LogcatWindowChanges.cap(s))
    }

    @Test fun markerLine_validatesIdentity() {
        assertEquals("LDOA-MANAGED-CASE MARKER BEGIN case=patched id=$id",
            LogcatWindowChanges.markerLine(true, "patched", id))
        for (bad in listOf("badcase", "", "ORIGINAL")) {
            try { LogcatWindowChanges.markerLine(true, bad, id); fail("accepted case $bad") } catch (e: IllegalArgumentException) {}
        }
        try { LogcatWindowChanges.markerLine(true, "patched", "short"); fail("accepted short id") } catch (e: IllegalArgumentException) {}
    }

    @Test fun render_carriesCaveatsAndEvents() {
        val cap = listOf(begin(), req(1800, 1949, 3), end()).joinToString("\n")
        val r = LogcatWindowChanges.parse(cap, "patched", id, false, true, true)
        val body = LogcatWindowChanges.render(r, "patched", id, 100L, 200L, cap)
        assertTrue(body.contains("absence of a line is UNKNOWN"))
        assertTrue(body.contains("order-only"))
        assertTrue(body.contains("SERVER-OBSERVED"))
        assertTrue(body.contains("NOT proof RRScreenSizeSet succeeded"))
        assertTrue(body.contains("privacy_scope"))
        assertTrue(body.contains("0 REQUEST 1800x1949"))
        assertTrue(body.contains("raw_capture_begin"))
        assertTrue(body.contains("case=patched marker=$id"))
    }
}
