package dev.phonelinux.proto

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * XCONTROL check-drawing arm: the vendored X server draws a known RGB
 * pattern into the root pixmap entirely on its own (vendored source:
 * third_party/termux-x11/lorie/src/main/cpp/lorie/InitOutput.c:279
 * drawSquares() - three centered squares, red/green/blue, side
 * min(W,H)/4, written DIRECTLY into the root window pixmap; option
 * parsed at InitOutput.c:379, NoListenAll = no X clients). No rootfs,
 * no guest session, no compositor.
 *
 * WHAT THIS ARM CAN AND CANNOT SHOW (no binary causal conclusions):
 *  - Squares VISIBLE in the screencap: the SIMPLE server-root draw path
 *    (root pixmap -> texture -> Android surface) presented THIS kind of
 *    content at THIS time on THIS emulator build. It does NOT prove the
 *    GNOME session's path (compositor GL, redirected output window,
 *    different dimensions/timing/route) presents; black session frames
 *    remain compatible with BOTH absent guest content AND a
 *    compositor-specific presentation failure.
 *  - Squares ABSENT: the pattern was not observed. Candidate causes
 *    include an invalid control (flag not taken, pattern queued before
 *    any surface existed and never re-armed), injection/layout/timing,
 *    or a presentation defect - absence alone does NOT establish which.
 *  The vendored renderer's size-mismatch early-return (renderer.cpp
 *  :921 redrawLocked, :949-958: on mismatch it clears drawRequested
 *  and sets waitingForBuffers, returning without drawing) is ONE known
 *  mechanism that can hold a surface black; not seeing its log line at
 *  one sampled moment does NOT exclude it at other moments.
 *  Emulator result: NOT Adreno, never a device verdict.
 *
 * Capture is PLAIN (Shot.save = UiAutomation.takeScreenshot + logcat
 * chunks; this arm registers no watcher and clicks no dialog).
 * Identity mirrors VirglGnomeSessionTest's strict parsing: tri-state
 * current-focus (mCurrentFocus + mFocusedApp, contradiction =>
 * UNKNOWN), timestamp-filtered own-pid renderer binds with the LATEST
 * entry deciding (a post-bind teardown flips to unbound), and the
 * final sample is taken AFTER the settle beat, immediately before the
 * screenshot.
 *
 * Ownership: the server is bound to THIS invocation by the pid
 * XServer.start returned (XServer.xServerPidForTest - the Process
 * handle is the ownership proof, never a /proc guess), then verified
 * by token-EXACT cmdline (entry class, :0, -check-drawing as full
 * NUL-separated tokens), matching UID, and a NONEMPTY starttime read
 * stably (equal reads bracketing the cmdline read). Teardown uses the
 * existing world mechanism (XServer.stopDesktopMode, which destroys
 * the tracked Process handle); "gone" is only /proc absent or a
 * CHANGED starttime - an UNREADABLE starttime is UNKNOWN, never gone,
 * and any teardown failure FAILS the test (a green run can never hide
 * a leaked owned server).
 */
class XServerCheckDrawingTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private enum class FocusState { FOCUSED, NOT_FOCUSED, UNKNOWN }
    private data class FocusObs(val excerpt: String, val state: FocusState)

    // Mirrors VirglGnomeSessionTest.parseFocusDump (private there).
    private fun parseFocusDump(raw: String): FocusObs {
        fun mentions(l: String): Boolean =
            l.contains("com.termux.x11.MainActivity") || l.contains("com.termux.x11/.MainActivity")
        val cur = raw.lineSequence().filter { it.contains("mCurrentFocus") }.toList()
        val app = raw.lineSequence().filter { it.contains("mFocusedApp") }.toList()
        val curT = cur.any { mentions(it) }
        val curO = cur.any { !mentions(it) }
        val appO = app.any { !mentions(it) }
        val appT = app.any { mentions(it) }
        val state = when {
            cur.isEmpty() -> FocusState.UNKNOWN
            curT && curO -> FocusState.UNKNOWN
            curT && appO -> FocusState.UNKNOWN
            curT -> FocusState.FOCUSED
            appT -> FocusState.UNKNOWN
            else -> FocusState.NOT_FOCUSED
        }
        val joined = (cur + app).joinToString(" ").replace('\n', ' ').take(300)
        val excerpt = if (joined.isNotEmpty()) joined else raw.replace('\n', ' ').take(300)
        return FocusObs(excerpt, state)
    }

    private fun currentFocusState(device: androidx.test.uiautomator.UiDevice): FocusObs {
        val raw = try {
            device.executeShellCommand("dumpsys window")
        } catch (t: Throwable) {
            return FocusObs("(dumpsys window threw: " + t.javaClass.simpleName + ")", FocusState.UNKNOWN)
        }
        return parseFocusDump(raw)
    }

    private fun parseLogcatThreadtimeTs(line: String): Long? {
        val m = Regex("""^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d)""").find(line)
            ?: return null
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.MONTH, m.groupValues[1].toInt() - 1)
        cal.set(java.util.Calendar.DAY_OF_MONTH, m.groupValues[2].toInt())
        cal.set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[3].toInt())
        cal.set(java.util.Calendar.MINUTE, m.groupValues[4].toInt())
        cal.set(java.util.Calendar.SECOND, m.groupValues[5].toInt())
        cal.set(java.util.Calendar.MILLISECOND, m.groupValues[6].toInt())
        return cal.timeInMillis
    }

    // Mirrors VirglGnomeSessionTest.latestOwnRendererBind: own-pid,
    // post-attach timestamped entries only; the LATEST decides, so a
    // later teardown (0x0 0 0) means NOT bound now - never a sticky flag.
    private fun latestOwnRendererBind(dump: String, appPid: Int, sinceMs: Long): String {
        var best: String? = null
        var bestTs = -1L
        var bestBound = false
        dump.lineSequence().forEach { line ->
            val m = Regex("""rendererSetWindow 0x([0-9a-f]+) (\d+) (\d+)""").find(line) ?: return@forEach
            val ts = parseLogcatThreadtimeTs(line) ?: return@forEach
            val pidm = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d\s+(\d+)\s""").find(line)
                ?: return@forEach
            if (pidm.groupValues[1].toInt() != appPid) return@forEach
            if (ts < sinceMs - 2_000) return@forEach
            if (ts >= bestTs) {
                bestTs = ts
                bestBound = m.groupValues[1] != "0" && m.groupValues[2].toInt() > 0 && m.groupValues[3].toInt() > 0
                best = line.trim()
            }
        }
        return if (best != null && bestBound) best!! else ""
    }

    private data class ServerIdent(val pid: Int, val start: String, val cmdline: String, val uid: Int)

    // Token-EXACT cmdline parse: full NUL-separated tokens, never
    // substring matching. Returns the token list, or null when unreadable.
    private fun cmdlineTokens(pid: Int): List<String>? = try {
        File("/proc/" + pid + "/cmdline").readBytes()
            .split(0.toByte()).map { String(it) }.filter { it.isNotEmpty() }
    } catch (t: Throwable) { null }

    private fun isXServerOnDisplay0(tokens: List<String>): Boolean =
        tokens.contains("com.termux.x11.CmdEntryPoint") && tokens.contains(":0")

    private fun isControlShape(tokens: List<String>): Boolean =
        isXServerOnDisplay0(tokens) && tokens.contains("-check-drawing")

    private fun procStartTick(pid: Int): String =
        try {
            File("/proc/" + pid + "/stat").readText()
                .substringAfterLast(") ").split(" ")[19]
        } catch (t: Throwable) { "" }

    private fun procUid(pid: Int): Int = try {
        File("/proc/" + pid + "/status").readText().lineSequence()
            .firstOrNull { it.startsWith("Uid:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toInt() ?: -1
    } catch (t: Throwable) { -1 }

    // Read starttime - cmdline - starttime: both starttime reads must be
    // NONEMPTY and EQUAL, else the process turned over mid-read (reject).
    private fun stableIdent(pid: Int): ServerIdent? {
        val s1 = procStartTick(pid)
        if (s1.isEmpty()) return null
        val tokens = cmdlineTokens(pid) ?: return null
        val s2 = procStartTick(pid)
        if (s2.isEmpty() || s1 != s2) return null
        return ServerIdent(pid, s1, tokens.joinToString(" "), procUid(pid))
    }

    // ALL live :0 X server processes (any shape), token-exact.
    private fun findAnyDisplay0Servers(): List<ServerIdent> {
        val found = mutableListOf<ServerIdent>()
        File("/proc").listFiles()?.forEach { p ->
            if (!p.name.all { it.isDigit() }) return@forEach
            val pid = p.name.toIntOrNull() ?: return@forEach
            val id = stableIdent(pid) ?: return@forEach
            val tokens = cmdlineTokens(pid) ?: return@forEach
            if (isXServerOnDisplay0(tokens)) found.add(id)
        }
        return found
    }

    private fun identityMatches(id: ServerIdent): Boolean {
        if (!File("/proc/" + id.pid).isDirectory) return false
        if (procUid(id.pid) != id.uid) return false
        val live = stableIdent(id.pid) ?: return false
        return live.start == id.start && live.cmdline == id.cmdline && live.uid == id.uid
    }

    // gone = /proc absent, or present with a READABLE starttime that
    // CHANGED (pid reused by something else). An UNREADABLE starttime
    // on a present dir is UNKNOWN (null), never gone.
    private fun goneState(id: ServerIdent): Boolean? {
        if (!File("/proc/" + id.pid).isDirectory) return true
        val s = procStartTick(id.pid)
        if (s.isEmpty()) return null
        return s != id.start
    }

    @Test fun checkDrawingControl(): Unit = runBlocking {
        val report = File(ctx.filesDir, "xcontrol-check-drawing.txt")
        report.delete()
        val out = StringBuilder()
        out.append("XCONTROL check-drawing arm (simple server-root presentation control; NO guest")
            .append(" content). Results constrain hypotheses ONLY - see the class doc for what a")
            .append(" visible or absent pattern can and cannot show; NO binary causal conclusion.\n")
        out.append("expected pattern (vendored InitOutput.c:279 drawSquares): three centered squares")
            .append(" red/green/blue, side min(W,H)/4, drawn by the server itself into the root")
            .append(" window pixmap; -check-drawing (InitOutput.c:379) sets NoListenAll.\n")

        val myUid = android.os.Process.myUid()
        var primary: Throwable? = null
        var teardownFailure: String? = null
        var srv: ServerIdent? = null
        val prevArgs = XServer.extraServerArgsForTest
        try {
            // World check FIRST: ANY pre-existing :0 X server (control
            // shape or ordinary) invalidates the arm - it is EVIDENCE,
            // never something to kill.
            val pre = findAnyDisplay0Servers()
            assertTrue("PRE-EXISTING :0 X server(s) present - control INVALID this invocation" +
                " (nothing started, nothing signaled): " +
                pre.joinToString(" | ") { "pid=" + it.pid + " start=" + it.start + " uid=" + it.uid +
                    " cmdline=[" + it.cmdline + "]" }, pre.isEmpty())

            XServer.extraServerArgsForTest = listOf("-check-drawing")
            XServer.start(ctx)
            Thread.sleep(2_000)

            // Ownership by construction: the pid THIS start() returned.
            val ownPid = XServer.xServerPidForTest()
            assertTrue("XServer.start returned no own server pid - ownership cannot be" +
                " established (stop, never infer)", ownPid != null)
            val pid = ownPid!!
            val id = stableIdent(pid)
            assertTrue("own server pid " + pid + " identity UNSTABLE/UNREADABLE" +
                " (starttime empty or turned over mid-read)", id != null)
            val sid = id!!
            assertTrue("own server uid " + sid.uid + " != app uid " + myUid, sid.uid == myUid)
            val tokens = cmdlineTokens(pid) ?: emptyList()
            assertTrue("own server cmdline lacks token-exact -check-drawing: [" +
                tokens.joinToString(" ") + "]", isControlShape(tokens))
            // Unique candidate: no OTHER control-shaped process may exist.
            val others = findAnyDisplay0Servers().filter { it.pid != pid && isControlShape(cmdlineTokens(it.pid) ?: emptyList()) }
            assertTrue("AMBIGUOUS world: other control-shaped server(s) besides own pid " +
                pid + ": " + others.joinToString(" | ") { "pid=" + it.pid }, others.isEmpty())
            srv = sid
            out.append("server-identity: pid=").append(sid.pid).append(" start=").append(sid.start)
                .append(" uid=").append(sid.uid).append(" cmdline=[").append(sid.cmdline).append("]")
                .append(" (pid returned by THIS start(); token-exact; uid matches; starttime read")
                .append(" stably; unique control candidate)\n")
            out.append("VERDICT control-arg: PROVEN (own launch pid carries token-exact -check-drawing)\n")

            val instr = InstrumentationRegistry.getInstrumentation()
            val device = androidx.test.uiautomator.UiDevice.getInstance(instr)
            val appPid = android.os.Process.myPid()
            val attachAt = System.currentTimeMillis()
            ctx.startActivity(
                android.content.Intent(ctx, com.termux.x11.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            var focusEver = ""
            var bindEver = ""
            val deadline = attachAt + 60_000
            while (System.currentTimeMillis() < deadline && (focusEver.isEmpty() || bindEver.isEmpty())) {
                if (focusEver.isEmpty()) {
                    val f = currentFocusState(device)
                    if (f.state == FocusState.FOCUSED) focusEver = f.excerpt
                }
                if (bindEver.isEmpty()) {
                    bindEver = latestOwnRendererBind(
                        device.executeShellCommand("logcat -d -v threadtime -t 400 -s gles-renderer:D"),
                        appPid, attachAt)
                }
                if (focusEver.isEmpty() || bindEver.isEmpty()) Thread.sleep(2_000)
            }
            out.append("attach-window evidence: attachAt=").append(attachAt)
                .append(" focusEver=").append(focusEver.isNotEmpty())
                .append(" bindEver=").append(bindEver.isNotEmpty())
                .append(if (bindEver.isNotEmpty()) " bindLine=[" + bindEver.take(200) + "]" else "")
                .append('\n')

            // Settle beat for root-pixmap presentation, THEN the decisive
            // sample immediately before the screenshot (focus now, latest
            // bind now - a post-bind teardown fails it -, server identity
            // intact now). Raw sample wall-times retained.
            Thread.sleep(3_000)
            val focusSampleAt = System.currentTimeMillis()
            val focusNow = currentFocusState(device)
            val bindSampleAt = System.currentTimeMillis()
            val bindNow = latestOwnRendererBind(
                device.executeShellCommand("logcat -d -v threadtime -t 400 -s gles-renderer:D"),
                appPid, attachAt)
            val serverNow = identityMatches(sid)
            out.append("final-sample: focusSampleAt=").append(focusSampleAt)
                .append(" bindSampleAt=").append(bindSampleAt)
                .append(" focusNow=").append(focusNow.state)
                .append(" bindNow=").append(if (bindNow.isNotEmpty()) "[" + bindNow.take(200) + "]" else "EMPTY")
                .append(" serverNow=").append(serverNow).append('\n')
            val identityOk = focusEver.isNotEmpty() && bindEver.isNotEmpty() &&
                focusNow.state == FocusState.FOCUSED && bindNow.isNotEmpty() && serverNow
            out.append("VERDICT bind-identity: ").append(
                if (identityOk) "ESTABLISHED at the final pre-capture sample"
                else "NOT-ESTABLISHED (see final-sample above; focusRaw=[" +
                    focusNow.excerpt.take(200) + "])").append('\n')
            assertTrue("control bind identity not established at the final sample", identityOk)

            Shot.save("xcontrol-check-drawing")
            out.append("VERDICT presentation: capture via Android-display screencap (shot pipeline,")
                .append(" name xcontrol-check-drawing); artifact arrival GATED CI-side; REVIEWER")
                .append(" pixel inspection interprets against the class doc's non-binary reading")
                .append(" guide\n")
            out.append("emulator result - NOT Adreno; constrains emulator hypotheses only\n")
        } catch (t: Throwable) {
            primary = t
        } finally {
            try {
                XServer.extraServerArgsForTest = prevArgs
            } catch (t: Throwable) {
                out.append("hook restore FAILED: ").append(t.message ?: t.javaClass.simpleName).append('\n')
                if (teardownFailure == null) teardownFailure = "hook restore threw"
            }
            // Teardown via the existing world mechanism ONLY when this arm
            // started an owned server (stopDesktopMode destroys the tracked
            // Process handle - ownership by construction). Verified after:
            // gone = /proc absent or CHANGED starttime; unreadable =
            // UNKNOWN and a teardown FAILURE, never green.
            val s = srv
            if (s != null) {
                try {
                    if (identityMatches(s)) {
                        XServer.stopDesktopMode(ctx)
                        var gone: Boolean? = null
                        var waits = 0
                        while (waits < 10 && gone != true) {
                            Thread.sleep(500)
                            gone = goneState(s)
                            waits++
                        }
                        when (gone) {
                            true -> out.append("control-server teardown: stopDesktopMode; pid=")
                                .append(s.pid).append(" GONE (proc absent or starttime changed)\n")
                            false -> {
                                out.append("control-server teardown: pid=").append(s.pid)
                                    .append(" STILL ALIVE after stopDesktopMode\n")
                                teardownFailure = "owned server " + s.pid + " still alive after teardown"
                            }
                            null -> {
                                out.append("control-server teardown: pid=").append(s.pid)
                                    .append(" teardown UNVERIFIABLE (starttime unreadable on a present")
                                    .append(" proc dir - NOT proof of gone)\n")
                                teardownFailure = "owned server " + s.pid + " teardown unverifiable"
                            }
                        }
                    } else {
                        out.append("control-server teardown: SKIPPED - identity no longer matches")
                            .append(" pid=").append(s.pid).append(" (nothing signaled; NOT proof the")
                            .append(" owned server is gone)\n")
                        teardownFailure = "owned server " + s.pid + " identity changed before teardown"
                    }
                } catch (t: Throwable) {
                    out.append("control-server teardown threw: ")
                        .append(t.message ?: t.javaClass.simpleName).append('\n')
                    teardownFailure = "teardown threw: " + (t.message ?: t.javaClass.simpleName)
                }
            }
            try {
                report.writeText(out.toString())
            } catch (t: Throwable) {
                android.util.Log.e("PHONEDIAG", "xcontrol report write FAILED: " +
                    (t.message ?: t.javaClass.simpleName))
                if (teardownFailure == null) teardownFailure = "report write threw"
            }
        }
        // A green run must never hide an incomplete teardown: teardown
        // failure fails the test, preserving the original exception as
        // suppressed when both exist.
        val tf = teardownFailure
        if (tf != null) {
            val e = AssertionError("xcontrol teardown/cleanup incomplete: " + tf)
            if (primary != null) e.addSuppressed(primary)
            throw e
        }
        if (primary != null) throw primary!!
        Unit
    }
}
