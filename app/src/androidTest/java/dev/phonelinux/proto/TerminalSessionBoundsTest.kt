package dev.phonelinux.proto

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bounds verification for the production TerminalSession scrollback/callback/
 * log changes (d5d373f): exercises the REAL compiled class on-device via
 * reflection on its private internals - no proot, no rootfs, no session
 * start, so the measurement cannot wedge behind guest provisioning.
 *
 * Asserts exactly, per mechanism:
 *  1. UI scrollback stays bounded with the truncation marker and the newest
 *     tail after a multi-MB append (quadratic-regression guard).
 *  2. A burst of notifyUpdate() calls coalesces to an immediate fire plus
 *     one trailing fire - the last chunk of a burst is never lost.
 *  3. The session log batches small appends (no per-chunk open/write/close
 *     storm) and the final flush preserves every line in order.
 */
@RunWith(AndroidJUnit4::class)
class TerminalSessionBoundsTest {

    private fun call0(s: TerminalSession, name: String) {
        val m = TerminalSession::class.java.getDeclaredMethod(name)
        m.isAccessible = true
        m.invoke(s)
    }

    private fun callStr(s: TerminalSession, name: String, v: String) {
        val m = TerminalSession::class.java.getDeclaredMethod(name, String::class.java)
        m.isAccessible = true
        m.invoke(s, v)
    }

    // JUnit timeout converts any regression into an attributed failure
    // instead of a silent invocation stall; these paths are pure JVM and
    // should finish in seconds, so 120s is generous.
    @Test(timeout = 120_000)
    fun uiScrollbackIsBoundedWithMarkerAndNewestTail() {
        Log.i("TerminalSessionBoundsTest", "ENTER uiScrollbackIsBoundedWithMarkerAndNewestTail")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val s = TerminalSession(ctx, File("/nonexistent-rootfs")) {}
        try {
            // Multi-MB ordered payload, fed in reader-sized chunks so the
            // trim path runs repeatedly, as in production.
            val chunk = StringBuilder()
            var i = 0
            while (chunk.length < 4096) { i++; chunk.append("seq ").append(i).append(" payload-payload\n") }
            val unit = chunk.toString()
            // Unique oldest line: seq numbers repeat every chunk, so they
            // cannot distinguish "oldest dropped" from "tail of a chunk".
            callStr(s, "appendToBuf", "UNIQUE-OLDEST-LINE\n")
            var appended = 0
            while (appended < 8 * 1024 * 1024) { callStr(s, "appendToBuf", unit); appended += unit.length }
            val snap = s.snapshot()
            val snapBytes = snap.toByteArray(Charsets.UTF_8).size
            // Bound: maxBufBytes plus one marker line (trim leaves <= 384K,
            // then up to a 4KB chunk + marker can regrow before next trim,
            // but never past max + one chunk + marker).
            assertTrue("snapshot must stay bounded, was $snapBytes", snapBytes <= 512 * 1024 + 4096 + 128)
            assertTrue("truncation marker at head", snap.startsWith("[...older output truncated"))
            assertTrue("newest tail kept", snap.contains("seq $i"))
            assertFalse("oldest content dropped", snap.contains("UNIQUE-OLDEST-LINE"))
        } finally {
            s.close()
        }
    }

    @Test(timeout = 120_000)
    fun updateCallbackCoalescesWithTrailingFire() {
        Log.i("TerminalSessionBoundsTest", "ENTER updateCallbackCoalescesWithTrailingFire")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val fires = AtomicInteger(0)
        val s = TerminalSession(ctx, File("/nonexistent-rootfs")) { fires.incrementAndGet() }
        try {
            repeat(500) { call0(s, "notifyUpdate") }
            val duringBurst = fires.get()
            assertTrue("burst must coalesce (expected <=3 immediate fires, got $duringBurst)", duringBurst in 1..3)
            Thread.sleep(300)
            val afterSettle = fires.get()
            assertTrue("trailing fire must deliver the burst end (during=$duringBurst after=$afterSettle)",
                afterSettle == duringBurst + 1)
        } finally {
            s.close()
        }
    }

    @Test(timeout = 120_000)
    fun sessionLogBatchesAndFinalFlushPreservesOrder() {
        Log.i("TerminalSessionBoundsTest", "ENTER sessionLogBatchesAndFinalFlushPreservesOrder")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val log = File(ctx.filesDir, "terminal-session.log")
        log.delete()
        val s = TerminalSession(ctx, File("/nonexistent-rootfs")) {}
        try {
            for (i in 1..5000) callStr(s, "logAppend", "logline $i\n")
            val total = (1..5000).sumOf { "logline $it\n".length }
            val onDiskBeforeFlush = if (log.isFile) log.length() else 0L
            assertTrue("small appends must batch (all $total bytes would mean no batching; got $onDiskBeforeFlush)",
                onDiskBeforeFlush < total)
            call0(s, "logFlush")
            val text = log.readText()
            assertEquals("final flush must persist every byte", total.toLong(), log.length())
            var last = -1
            for (i in intArrayOf(1, 999, 2500, 4999, 5000)) {
                val idx = text.indexOf("logline $i\n")
                assertTrue("logline $i missing", idx >= 0)
                assertTrue("logline $i out of order", idx > last)
                last = idx
            }
        } finally {
            s.close()
            log.delete()
        }
    }
}
