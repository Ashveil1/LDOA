package dev.phonelinux.proto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Regression coverage for the production log-window unit discipline
 *  (XServer.logWindow and its consumers): every offset is a CHAR offset
 *  into a single readText() snapshot, clamped against THAT snapshot;
 *  rotation/truncation fails closed with an EMPTY window - never a
 *  whole-file fallback that could match a stale pre-rotation launch id,
 *  bus nonce or verdict line (crash class run 41283803508; stale-match
 *  class run 41303335159). Multi-byte UTF-8 before the base (mutter
 *  warnings carry curly quotes) is the trigger condition for both. */
@RunWith(AndroidJUnit4::class)
class XServerLogWindowTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val spawned = ArrayList<Process>()
    private val logs = ArrayList<File>()

    private fun newLog(): File {
        val f = File(ctx.filesDir, "test-logwindow-" + System.nanoTime() + ".log")
        logs.add(f)
        return f
    }

    private fun sleeper(): Process =
        Runtime.getRuntime().exec(arrayOf("sh", "-c", "sleep 60")).also { spawned.add(it) }

    @After fun cleanup() {
        for (p in spawned) try { p.destroy() } catch (t: Throwable) { }
        for (f in logs) try { f.delete() } catch (t: Throwable) { }
    }

    private val fallbackVerdict = "gnome-launch: fallback shell missing after 90s"

    /** nonASCII BEFORE BASE: a char base must expose lines appended after
     *  it even when earlier content holds multi-byte UTF-8 (each curly
     *  quote is 3 bytes but 1 char - a byte base would overshoot). */
    @Test fun charBaseWithNonAsciiPrefixSeesAppendedVerdict() {
        val log = newLog()
        log.writeText("mutter: can\u2019t get \u201CDRI3\u201D device\n")
        val base = log.readText().length.toLong()
        log.appendText(fallbackVerdict + "\n")
        val p = sleeper()
        assertEquals("char base must expose the appended verdict",
            XServer.GateNullClass.DEFINITIVE_SCRIPT, XServer.classifyGateNull(p, log, base))
    }

    /** Oversized (byte-style) base: enough multi-byte UTF-8 before the
     *  base makes base > TOTAL snapshot chars after the append - the
     *  pre-fix code sliced readText() with that byte index clamped only
     *  against the BYTE length and crashed (StringIndexOutOfBounds, run
     *  41283803508: length=123692 index=123703). The window must be
     *  EMPTY: no crash, no match, readiness stays unknown (fail closed). */
    @Test fun oversizedByteStyleBaseFailsClosedWithoutCrash() {
        val log = newLog()
        // 40 curly quotes: 40 chars but 120 bytes - the byte surplus (80)
        // exceeds the appended verdict's 48 bytes, so a byte base lands
        // PAST the whole char snapshot (the historical crash shape).
        log.writeText("mutter: " + "\u201C".repeat(40) + "\n")
        val byteStyleBase = log.length() // BYTES, deliberately wrong unit
        log.appendText(fallbackVerdict + "\n")
        val p = sleeper()
        assertEquals("oversized base must fail closed, not crash",
            XServer.GateNullClass.STILL_STARTING, XServer.classifyGateNull(p, log, byteStyleBase))
    }

    /** ROTATION/TRUNCATION: the file restarts small; its whole content is
     *  a stale-looking verdict from the previous generation. The window
     *  must be EMPTY - a whole-file fallback would accept the stale line. */
    @Test fun rotationTruncationNeverAcceptsWholeFileStaleContent() {
        val log = newLog()
        log.writeText("old world: " + "x".repeat(200) + "\n")
        val base = log.readText().length.toLong()
        log.writeText(fallbackVerdict + "\n") // rotation: file restarts
        val p = sleeper()
        assertEquals("post-rotation whole-file stale verdict must NOT be accepted",
            XServer.GateNullClass.STILL_STARTING, XServer.classifyGateNull(p, log, base))
    }

    /** Process death classification is independent of the window. */
    @Test fun deadProcessClassifiesDefinitiveExitedRegardlessOfWindow() {
        val log = newLog()
        log.writeText("anything\n")
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "exit 0")).also { spawned.add(it) }
        p.waitFor()
        assertEquals(XServer.GateNullClass.DEFINITIVE_EXITED,
            XServer.classifyGateNull(p, log, 0L))
    }

    /** APPEND after a non-ASCII-prefixed base: the readiness gate must see
     *  this launch's bus line + matching registration marker. */
    @Test fun shellGateSeesMarkerAcrossNonAsciiCharWindow() {
        val log = newLog()
        log.writeText("mutter: \u201Cwarn\u201D\n")
        val base = log.readText().length.toLong()
        log.appendText("gnome-launch: session bus unix:path=/tmp/bus-WIN,guid=WIN nonce=n1\n")
        log.appendText("gnome-launch: shell registered launch=1 bus=unix:path=/tmp/bus-WIN,guid=WIN\n")
        val p = sleeper()
        val hit = XServer.awaitShellRegistered(p, log, base, 5_000)
        assertTrue("marker across a non-ASCII-prefixed char window must gate: " + hit,
            hit != null && hit.contains("guid=WIN"))
    }

    /** ROTATION before the gate: a whole-file stale marker must not pass
     *  (window empty until the file regrows past the base). */
    @Test fun shellGateAfterRotationSeesNothing() {
        val log = newLog()
        log.writeText("old world: " + "x".repeat(200) + "\n")
        val base = log.readText().length.toLong()
        log.writeText("gnome-launch: shell registered launch=1 bus=unix:path=/tmp/bus-ROT,guid=ROT\n")
        val p = sleeper()
        val hit = XServer.awaitShellRegistered(p, log, base, 3_000)
        assertNull("post-rotation stale marker must not gate: " + hit, hit)
    }
}
