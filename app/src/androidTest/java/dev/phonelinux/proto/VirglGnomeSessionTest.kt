package dev.phonelinux.proto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * P3 real-session qualification: an opted-IN GNOME session (the actual
 * X/gnome-shell path, not a side probe) on the virgl renderer.
 *
 * Verdicts are SEPARATE and named for what they actually establish:
 *  - READINESS: the LATEST state line inside THIS launch's generation
 *    slice (exact launch-id token match; any following generation's
 *    content excluded; log shrink/rotation fails closed). An earlier
 *    alive never masks a later failure inside the same generation, and
 *    guest failure output after the last alive voids it. Readiness
 *    alone proves NOTHING about the renderer.
 *  - COMPOSITOR-CONFIGURED-ROUTE: a gnome-shell process OWNED by this
 *    session (PPid-ancestry to this generation's gnome-session pid -
 *    time freshness alone is candidate evidence, never ownership)
 *    carries GALLIUM_DRIVER=virpipe in its OWN environ. This proves the
 *    compositor was LAUNCHED on the virgl route. It does NOT prove the
 *    compositor's GL context initialized through virgl, and says
 *    nothing about displayed pixels.
 *  - COMPOSITOR-RENDERER: UNKNOWN unless evidence attributable to
 *    gnome-shell's actual GL context exists. Keyword-filtered log lines
 *    are recorded RAW as CANDIDATES - never authenticated process
 *    attribution, never an automatic verdict upgrade. Socket
 *    fd-to-inode correlation is reported when readable (net/unix is
 *    EACCES on this emulator, so UNKNOWN is expected here).
 *  - GUEST-XCONTENT: guest-side X-root image capture (asset script
 *    probes xwd/import/gnome-screenshot/ffmpeg in that order, unique
 *    per-launch path, confirmed stale removal, 1KiB..64MiB acceptance
 *    bounds, partial bytes retained as evidence) gated ONLY on session
 *    identity (current generation + live session) - Android focus is
 *    NOT a prerequisite, so a black Android screenshot can never be
 *    conflated with proof of absent guest X content. A nonblank image
 *    is NOT automatically a desktop: reviewer pixel inspection decides.
 *  - PRESENTATION (Android display): shot pipeline gated on
 *    capture-target IDENTITY - component/surface/content axes, content
 *    = existence corroboration only - plus the CI-side artifact-arrival
 *    gate. STRICT and unchanged; REVIEWER-VERIFIED presentation stays
 *    PENDING until the artifact image is inspected for the intended X
 *    desktop. ANR handling: at most ONE verified narrow Wait dismissal
 *    per arm; a recurrence ENDS focus polling early WITH EVIDENCE
 *    instead of manufacturing focus (run 41348620342: 22 dismissals in
 *    one window while the wedged launcher re-posted the dialog -
 *    pathological recovery, never a safety qualification).
 *  - STOP-CLEANUP: structured worker capture on the SESSION's render
 *    server, owned stop, identity-matched stop-return sampling,
 *    tri-state post checks.
 *  - SMOOTHNESS: NOT MEASURED - excluded from this qualification.
 *
 * There is NO single global "QUALIFICATION passed" line: the report
 * ends with separate EXECUTION / CHECKS / COMPOSITOR-RENDERER /
 * PRESENTATION declarations so an executed-checks pass can never imply
 * a verified compositor renderer.
 *
 * Cleanup or report-persistence failures FAIL the test (never a green
 * suite with swallowed errors); the original failure is preserved
 * (later failures added as suppressed), diagnostic evidence is kept,
 * and the flag restore always runs.
 *
 * Emulator only (SwiftShader host): nothing here is an Adreno or
 * smoothness claim. Full lane only (needs the provisioned GNOME).
 */
@RunWith(AndroidJUnit4::class)
class VirglGnomeSessionTest {
    private val TAG = "VirglGnomeSessionTest"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val flag get() = File(ctx.filesDir, ".ldoa-virgl-optin")
    private val logFile get() = File(ctx.filesDir, "guest-gnome.log")

    /** Post-base log window, FAIL CLOSED on shrink/rotation. */
    private class CaptureIdentity(val ok: Boolean, val summary: String, val failure: String)

    /** /proc/<pid>/stat field 22 (starttime), parsed AFTER the last ')'
     *  because comm may contain spaces/parens; null when unreadable. A
     *  bare /proc/PID directory does not exclude PID reuse - starttime
     *  revalidation does. */
    private fun procStartTick(pid: Int): String? {
        return try {
            File("/proc/" + pid + "/stat").readText().substringAfterLast(") ").split(" ")[19]
        } catch (t: Throwable) { null }
    }

    private fun gsStartMatches(gsPid: Int, recorded: String?): Boolean =
        recorded != null && procStartTick(gsPid) == recorded

    /** Device-side SHA-256 of a delivered helper binary (recorded pair
     * with the CI log's runner-side sha256; not a gate). */
    private fun sha256Hex(f: File): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }

    /**
     * Checked removal of a KNOWN owned guest file. Never swallows the
     * result: NoSuchFileException = already absent (no leftover);
     * anything else is recorded with the exact error and a re-checked
     * existence state (UNKNOWN when even that check fails).
     */
    private fun removeOwnedGuestFile(rootfs: File, rel: String): String {
        val f = File(rootfs, rel)
        return try {
            java.nio.file.Files.delete(f.toPath())
            "removed (owned path $rel)"
        } catch (nsf: java.nio.file.NoSuchFileException) {
            "already-absent (NoSuchFileException - no leftover)"
        } catch (t: Throwable) {
            val stillThere = try { f.exists().toString() } catch (e: Throwable) {
                "UNKNOWN (" + e.javaClass.simpleName + ")"
            }
            "REMOVAL-FAILED [" + (t.message ?: t.javaClass.simpleName) + "] exists=" + stillThere +
                " (owned path " + rel + " may remain in guest tmp; recorded, not silently ignored)"
        }
    }

    /**
     * Validate the helper's PPM artifact BEFORE accepting it: a fresh
     * (mtime at/after invocation, 1s slack), REGULAR, non-symlink file
     * within the bounded-output cap, with a well-formed P6 header
     * (comments allowed, width 1..32, height = 5 stacked patches,
     * maxval 255) and a body of EXACTLY 3*w*h bytes. Returns null when
     * valid, else the rejection reason. XP_RESULT text alone never
     * accepts output.
     */
    private fun validateXmapPpm(f: File, invokedAtMs: Long): String? {
        val p = f.toPath()
        if (!java.nio.file.Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            return "absent, symlink, or non-regular file"
        if (f.lastModified() < invokedAtMs - 1_000)
            return "stale (mtime " + f.lastModified() + " before invocation " + invokedAtMs + ")"
        val len = f.length()
        if (len < 9 || len > 66_000) return "size " + len + " outside 9..66000"
        // 4 KiB header window: the helper writes ~0.5 KiB of comments;
        // a header beyond this window rejects (fail-closed, never a
        // false ACCEPT).
        val head = ByteArray(4096)
        val n = f.inputStream().use { it.read(head) }
        if (n < 9) return "header shorter than 9 bytes"
        var i = 0
        fun tok(): String? {
            while (i < n) {
                val ch = head[i].toInt().toChar()
                if (ch == '#') { while (i < n && head[i].toInt() != 10) i++ }
                else if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') i++
                else break
            }
            val start = i
            while (i < n && head[i].toInt() > 32 && head[i].toInt() < 127) i++
            return if (i > start) String(head, start, i - start) else null
        }
        if (tok() != "P6") return "bad magic"
        val w = tok()?.toIntOrNull() ?: return "missing width"
        val h = tok()?.toIntOrNull() ?: return "missing height"
        val maxval = tok()?.toIntOrNull() ?: return "missing maxval"
        if (w < 1 || w > 32) return "width " + w + " outside 1..32"
        if (h != w * 5) return "height " + h + " != 5 stacked patches of " + w
        if (maxval != 255) return "maxval " + maxval + " != 255"
        if (i < n && head[i].toInt() == 9 || i < n && head[i].toInt() == 10 ||
            i < n && head[i].toInt() == 13 || i < n && head[i].toInt() == 32) i++
        val body = len - i
        if (body != 3L * w * h) return "body " + body + " != 3*" + w + "*" + h
        return null
    }


    /**
     * Capture-target IDENTITY for the shot-pipeline fallback: make the X
     * view foreground and PROVE the display shows THIS session's desktop
     * before any capture, on three axes with INDEPENDENT failure modes:
     *   component: the focused window is com.termux.x11.MainActivity
     *     (component-level, not package: the same package could be showing
     *     a loading or error screen);
     *   surface: the LATEST gles-renderer rendererSetWindow event FOR THIS
     *     APP PROCESS at/after this attach is a real bind (0x<nonzero>,
     *     W,H > 0) - a later 0x0 teardown invalidates an earlier bind, and
     *     another process's renderer lines never qualify (the lane's
     *     observed bind/teardown marker: run 41331931845 bind 17:51:55.801,
     *     teardown 17:54:22.362);
     *   content: THIS generation's :0 answers xwininfo -root -children and
     *     has client windows - EXISTENCE corroboration ONLY, never
     *     mapped/viewable proof; the reviewer pixel inspection stays the
     *     decisive presentation check.
     * Independently cached positives can drift apart, so CURRENT focus,
     * CURRENT surface state and CURRENT session liveness/generation are
     * re-sampled after dialog dismissal, immediately before the capture;
     * every raw dump tail is preserved in the failure evidence on
     * timeout/failure. Grounded activity behavior (vendored
     * MainActivity.java onCreate): the activity is VIEW-ONLY - it spawns
     * no X server and no session (server = XServer.start's app_process
     * CmdEntryPoint; session = launchOrReuseGnomeSession) - and this
     * helper never calls launchOrReuseGnomeSession, so the current
     * session generation is untouched. Fails closed with the missing
     * element named.
     */
    private fun establishDesktopCaptureIdentity(launchId: String, rootfs: File, gsPid: Int): CaptureIdentity {
        val instr = InstrumentationRegistry.getInstrumentation()
        val device = androidx.test.uiautomator.UiDevice.getInstance(instr)
        val appPid = android.os.Process.myPid()
        val attachAt = System.currentTimeMillis()
        ctx.startActivity(
            android.content.Intent(ctx, com.termux.x11.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        // Obstruction handling BEFORE the poll, EVIDENCE FIRST: record
        // the pre-dismissal focus state (and a screenshot when an ANR
        // signature is present) BEFORE anything is clicked, then dismiss
        // ONLY verified launcher-ANR dialogs via Shot's narrow path
        // (allowlisted package in the title + Wait scoped to the title's
        // own dialog subtree; no watcher registration, no "Allow"
        // prompts, no "Close app", every click PHONEDIAG-logged first).
        // Run 41343293710: a launcher ANR at 19:48 left its dialog
        // holding mCurrentFocus through the whole 180s window.
        // Per-arm dismissal budget (run 41348620342: 22 verified narrow
        // dismissals in one window while the wedged launcher re-posted
        // the dialog every few seconds - pathological recovery, not a
        // safety qualification): at most ONE verified dismissal in this
        // phase; a recurrence ENDS focus polling early WITH EVIDENCE.
        // The instrumentation-wide ANR watcher (registered on the Shot
        // pipeline's dialog path) would keep auto-clicking Wait on every
        // UiAutomator stall and defeat the budget, so it is REMOVED for
        // this phase - only this narrow path can click, once.
        device.removeWatcher("phonelinux-anr")
        android.util.Log.i("PHONEDIAG", "P3 identity: instrumentation ANR watcher removed - arm" +
            " dismissal budget is ONE verified narrow click; recurrence = fail-fast with evidence")
        val preDismiss = currentFocusState(device)
        if (preDismiss.excerpt.contains("Not Responding") || preDismiss.excerpt.contains("isn't responding")) {
            try {
                Shot.save("virgl-session-anr-obstruction")
            } catch (t: Throwable) {
                Log.i(TAG, "pre-dismissal obstruction screenshot failed (auxiliary): " +
                    (t.message ?: t.javaClass.simpleName))
            }
        }
        // THE arm's ONE allowed dismissal (budget: 1).
        val armDismissals = Shot.dismissVerifiedLauncherAnr(device, maxDismissals = 1)
        var focusLine = ""
        var rendererLine = ""
        var childCount = -1
        var treeTail = ""
        var focusRawTail = ""
        var rendererRawTail = ""
        var anrRecurrence = false
        val deadline = attachAt + 180_000
        while (System.currentTimeMillis() < deadline) {
            // Recurrence after the arm's ONE allowed dismissal: DETECT
            // ONLY (no click) and END focus polling early WITH EVIDENCE -
            // out-clicking a re-posting dialog manufactures focus.
            if (focusLine.isEmpty() && Shot.detectVerifiedLauncherAnr(device)) {
                anrRecurrence = true
                focusRawTail = currentFocusState(device).excerpt
                break
            }
            if (focusLine.isEmpty()) {
                // executeShellCommand whitespace-splits and execs WITHOUT a
                // shell: a "| grep" suffix reaches dumpsys as ARGUMENTS
                // (run 41336836798 focusRaw: "Bad window command, or no
                // windows match: |"). Full dump, filtered in-process.
                val f = currentFocusState(device)
                focusRawTail = f.excerpt
                if (f.state == FocusState.FOCUSED) focusLine = focusRawTail
            }
            if (rendererLine.isEmpty()) {
                val r = device.executeShellCommand("logcat -d -v threadtime -t 800 -s gles-renderer:D")
                rendererRawTail = r.lineSequence().toList().takeLast(3).joinToString(" | ").take(300)
                rendererLine = latestOwnRendererBind(r, appPid, attachAt)
            }
            if (childCount < 0) {
                val t = Proot.runGuest(ctx, rootfs,
                    listOf("/bin/sh", "-c", "export DISPLAY=:0; xwininfo -root -children 2>/dev/null | tail -25"),
                    extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"),
                    timeoutMs = 60_000)
                treeTail = t.stdout.trim()
                // Header lines start with "xwininfo:"/"Root"/"Parent"; only
                // CLIENT window lines start (after indent) with 0x.
                // Existence corroboration only - NOT mapped/viewable proof.
                val children = treeTail.lineSequence()
                    .count { it.trimStart().startsWith("0x") }
                if (t.exitCode == 0 && children >= 1) childCount = children
            }
            if (focusLine.isNotEmpty() && rendererLine.isNotEmpty() && childCount >= 0) break
            Thread.sleep(3_000)
        }
        // Failure evidence BEFORE any dismissal: the actual screen while
        // identity was missing (auxiliary - never masks the real failure).
        if (focusLine.isEmpty() || rendererLine.isEmpty() || childCount < 0) {
            try {
                Shot.save("virgl-session-identity-blocked")
            } catch (t: Throwable) {
                Log.i(TAG, "identity-blocked screenshot failed (auxiliary evidence only): " +
                    (t.message ?: t.javaClass.simpleName))
            }
        }
        // Budget spent: DETECT ONLY (no click) at the resample point.
        val anrPresentAtResample = Shot.detectVerifiedLauncherAnr(device)
        device.waitForIdle(2_000)
        // CURRENT-state re-sample immediately before any capture: focus can
        // change during dismissal, the surface can tear down after an
        // earlier bind, the session can die or a new generation can start.
        val focusNowObs = currentFocusState(device)
        val focusNowOk = focusNowObs.state == FocusState.FOCUSED
        val surfaceNow = latestOwnRendererBind(
            device.executeShellCommand("logcat -d -v threadtime -t 800 -s gles-renderer:D"),
            appPid, attachAt)
        val sessionAliveNow = File("/proc/" + gsPid).isDirectory
        val generationNow = XServer.currentLaunchIdForTest()
        val generationOk = generationNow == launchId
        val ok = focusLine.isNotEmpty() && rendererLine.isNotEmpty() && childCount >= 0 &&
            focusNowOk && surfaceNow.isNotEmpty() && sessionAliveNow && generationOk
        val summary = "launchId=" + launchId +
            " focusEver=[" + (if (focusLine.isEmpty()) "MISSING(last=" + focusNowObs.state + ")" else focusLine) + "]" +
            " surfaceEver=[" + (if (rendererLine.isEmpty()) "MISSING" else rendererLine.take(200)) + "]" +
            " sessionClientWindows=" + (if (childCount < 0) "MISSING" else childCount.toString()) +
            " (existence corroboration, not viewability proof)" +
            " | at-capture: focusNow=" + focusNowOk + " surfaceNow=" + surfaceNow.isNotEmpty() +
            " sessionAliveNow=" + sessionAliveNow + " generationNow=" + generationNow +
            " (attachAt=" + attachAt + ", 180s bound; armDismissals=" + armDismissals +
            " anrRecurrence=" + anrRecurrence + " anrPresentAtResample=" + anrPresentAtResample + ")"
        val failure = "focusEver=" + focusLine.isNotEmpty() +
            " surfaceEver=" + rendererLine.isNotEmpty() +
            " sessionClientWindows=" + childCount +
            " focusNow=" + focusNowOk + "(" + focusNowObs.state + ") surfaceNow=" + surfaceNow.isNotEmpty() +
            " sessionAliveNow=" + sessionAliveNow + " generationNow=" + generationNow +
            " | focusRaw=[" + focusRawTail + "] rendererRaw=[" + rendererRawTail + "]" +
            " treeTail=[" + treeTail.replace('\n', ' ').take(300) + "]" +
            " anrRecurrence=" + anrRecurrence + " anrPresentAtResample=" + anrPresentAtResample +
            " (dismissal budget: at most ONE verified narrow click per arm)"
        return CaptureIdentity(ok, summary, failure)
    }

    /** Current window focus is tri-state: FOCUSED only when an mCurrentFocus
     *  line names MainActivity and no other focus line contradicts it;
     *  NOT_FOCUSED when parseable lines consistently name another component;
     *  UNKNOWN when nothing parses or displays disagree. mFocusedApp alone is
     *  NEVER proof: it names the focused app, not the focused window, and a
     *  conflicting mCurrentFocus wins. The state is computed from the FULL
     *  parsed focus lines BEFORE the diagnostic excerpt is truncated to 300
     *  chars, so a target line beyond the excerpt still decides. */
    private enum class FocusState { FOCUSED, NOT_FOCUSED, UNKNOWN }
    private data class FocusObs(val excerpt: String, val state: FocusState)

    private fun currentFocusState(device: androidx.test.uiautomator.UiDevice): FocusObs {
        val raw = try {
            device.executeShellCommand("dumpsys window")
        } catch (t: Throwable) {
            return FocusObs("(dumpsys window threw: " + t.javaClass.simpleName + ")", FocusState.UNKNOWN)
        }
        return parseFocusDump(raw)
    }

    /** Pure parser (mirror-tested): UiDevice.executeShellCommand whitespace-
     *  splits and execs WITHOUT a shell (run 41336836798: a "| grep" suffix
     *  reached dumpsys as arguments), so the full dump is filtered in-process.
     *  The excerpt preserves the raw focus lines as failure evidence; when no
     *  focus lines parse, it preserves the dump head instead. */
    private fun parseFocusDump(raw: String): FocusObs {
        // dumpsys names components fully-qualified (com.termux.x11/com.termux.
        // x11.MainActivity) or compact (com.termux.x11/.MainActivity) depending
        // on line and version; a mention is either form of OUR component.
        fun mentions(l: String): Boolean =
            l.contains("com.termux.x11.MainActivity") || l.contains("com.termux.x11/.MainActivity")
        val cur = raw.lineSequence().filter { it.contains("mCurrentFocus") }.toList()
        val app = raw.lineSequence().filter { it.contains("mFocusedApp") }.toList()
        val curT = cur.any { mentions(it) }
        val curO = cur.any { !mentions(it) }
        val appO = app.any { !mentions(it) }
        val appT = app.any { mentions(it) }
        val state = when {
            cur.isEmpty() -> FocusState.UNKNOWN       // unparseable display state
            curT && curO -> FocusState.UNKNOWN        // displays disagree
            curT && appO -> FocusState.UNKNOWN        // focused-app contradicts current-focus
            curT -> FocusState.FOCUSED                // current focused window IS MainActivity
            appT -> FocusState.UNKNOWN                // mFocusedApp alone: not proof
            else -> FocusState.NOT_FOCUSED            // parseable, consistently elsewhere
        }
        val joined = (cur + app).joinToString(" ").replace('\n', ' ').take(300)
        val excerpt = if (joined.isNotEmpty()) joined else raw.replace('\n', ' ').take(300)
        return FocusObs(excerpt, state)
    }

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

    private fun parseLogcatThreadtimeTs(line: String): Long? {
        // threadtime prefix: "MM-DD HH:MM:SS.mmm" (device local, no year)
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

    private fun window(base: Long): String? {
        if (!logFile.isFile) return ""
        val content = logFile.readText()
        if (content.length < base) return null
        return content.substring(base.toInt())
    }

    /** THIS launch's generation slice: from its EXACT launch-id line
     *  (token match - the character after the id must end the line, so
     *  id "A" never matches id "AB") to the next launch's id line (any
     *  later generation's content is excluded) or end of window.
     *  Null = this launch's id line not present (yet). */
    private fun generationSlice(w: String, launchId: String): String? {
        val idLine = "gnome-launch: launch id=" + launchId
        var idx = -1
        var from = 0
        while (true) {
            val i = w.indexOf(idLine, from)
            if (i < 0) break
            val end = i + idLine.length
            if (end >= w.length || w[end] == '\n' || w[end] == '\r') idx = i
            from = i + 1
        }
        if (idx < 0) return null
        val next = w.indexOf("gnome-launch: launch id=", idx + idLine.length)
        return w.substring(idx, if (next < 0) w.length else next)
    }

    /** Runtime ticks-per-second via sysconf - never a hardcoded
     *  USER_HZ. -1 = unavailable (freshness then unverifiable). */
    private fun clkTck(): Long = try {
        android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
    } catch (t: Throwable) { -1L }

    /** Own-uid /proc scan: (pid, ppid, comm) triples + a partial flag.
     *  partial=true when ANY own-uid entry was unreadable or
     *  unparsable - completeness unproven (fail closed, never zero). */
    private fun scanOwnUidProcs(): Pair<List<Triple<Int, Int, String>>, Boolean> {
        val myUid = android.os.Process.myUid()
        val out = ArrayList<Triple<Int, Int, String>>()
        var partial = false
        val dir = File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) }
            ?: return Pair(out, true)
        for (f in dir) {
            val pid = try { f.name.toInt() } catch (t: Throwable) { continue }
            val st = try { android.system.Os.stat(f.absolutePath) } catch (t: Throwable) { continue }
            if (st.st_uid != myUid) continue
            val statTxt = try { File(f, "stat").readText() } catch (t: Throwable) { partial = true; continue }
            val close = statTxt.lastIndexOf(')')
            if (close < 0) { partial = true; continue }
            val name = statTxt.substring(statTxt.indexOf('(') + 1, close)
            val rest = statTxt.substring(close + 1).trim().split(Regex("\\s+"))
            if (rest.size < 2) { partial = true; continue }
            val ppid = try { rest[1].toInt() } catch (t: Throwable) { partial = true; continue }
            out.add(Triple(pid, ppid, name))
        }
        return Pair(out, partial)
    }

    /** Own-uid vtest processes attributed by the launch's
     *  generation-unique SOCKET PATH in their live cmdline:
     *  Triple(pids-owned-by-this-launch, other-own-uid-vtest-count,
     *  partial). A process whose cmdline carries the binary but not
     *  this socket path is NOT this launch's - recorded, never
     *  attributed, never signaled. An UNREADABLE own-uid cmdline cannot
     *  be attributed either way, so partial=true and "no residual" is
     *  unproven. */
    private fun scanOwnUidVirglForSocket(socketPath: String): Triple<List<Int>, Int, Boolean> {
        val procs = scanOwnUidProcs()
        val matched = ArrayList<Int>()
        var other = 0
        var partial = procs.second
        for (t in procs.first) {
            val cmd = try { File("/proc/" + t.first + "/cmdline").readText() }
                catch (e: Throwable) { partial = true; continue }
            if (!cmd.contains("libvirgl_test_server.so")) continue
            if (cmd.contains(socketPath)) matched.add(t.first) else other++
        }
        return Triple(matched, other, partial)
    }

    /** PPid-ancestry walk: TRUE = [ancestor] is on [pid]'s chain (owned
     *  by that process tree); FALSE = chain fully walked to init
     *  without it; NULL = walk broke (unreadable/depth-bound) =
     *  UNKNOWN, never silently false. */
    private fun ancestryContains(pid: Int, ancestor: Int): Boolean? {
        var cur = pid
        repeat(32) {
            if (cur == ancestor) return true
            if (cur <= 1) return false
            val st = try { File("/proc/" + cur + "/stat").readText() } catch (t: Throwable) { return null }
            val close = st.lastIndexOf(')')
            if (close < 0) return null
            val rest = st.substring(close + 1).trim().split(Regex("\\s+"))
            if (rest.size < 2) return null
            cur = rest[1].toIntOrNull() ?: return null
        }
        return null
    }

    private data class ShellScan(
        val owned: List<VirglTestCommon.ProcObs>,
        val timeOnlyCandidates: List<VirglTestCommon.ProcObs>,
        val ancestryUnknown: Int,
        val staleGeneration: Int,
        val unreadable: Int,
        val foreignUid: Int,
        val cmdlineUnreadable: Int)

    /** gnome-shell processes of THIS session generation. Ownership proof
     *  is PPid-ancestry to [gsPid] (this generation's gnome-session pid
     *  from its own alive marker). Time freshness (starttime vs launch
     *  uptime, runtime clk_tck) makes only a CANDIDATE - recorded,
     *  labeled, never counted as owned. Every reject/unknown class is
     *  COUNTED, including cmdline read failures (an unreadable entry
     *  could BE the shell); /proc enumeration failure fails the test. */
    private fun findGnomeShell(gsPid: Int, minStartSec: Double, tck: Long): ShellScan {
        val owned = ArrayList<VirglTestCommon.ProcObs>()
        val timeOnly = ArrayList<VirglTestCommon.ProcObs>()
        var ancUnknown = 0; var stale = 0; var unreadable = 0; var foreign = 0; var cmdUnreadable = 0
        val entries = File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) }
            ?: throw AssertionError("/proc enumeration failed - shell discovery impossible (fail-closed)")
        for (f in entries) {
            // 0.toChar() is the NUL delimiter (avoids a bare NUL escape in source).
            val argv0 = try {
                File(f, "cmdline").readBytes().toString(Charsets.UTF_8).split(0.toChar()).firstOrNull()
            } catch (t: Throwable) { null }
            if (argv0 == null) { cmdUnreadable++; continue }
            if (argv0.substringAfterLast('/') != "gnome-shell") continue
            val st = try { File(f, "stat").readText() } catch (t: Throwable) { unreadable++; continue }
            val close = st.lastIndexOf(')')
            if (close < 0) { unreadable++; continue }
            val rest = st.substring(close + 1).trim().split(Regex("\\s+"))
            if (rest.size < 20) { unreadable++; continue }
            val startTicks = rest[19].toLongOrNull()
            if (startTicks == null) { unreadable++; continue }
            val uid = try { android.system.Os.stat(f.absolutePath).st_uid }
                      catch (t: Throwable) { unreadable++; continue }
            if (uid != android.os.Process.myUid()) { foreign++; continue }
            val pid = f.name.toInt()
            val anc = ancestryContains(pid, gsPid)
            if (anc == true) { owned.add(VirglTestCommon.ProcObs(pid, rest[0], rest[19], uid)); continue }
            if (anc == null) { ancUnknown++; continue }
            // tck <= 0 or minStartSec < 0: freshness unverifiable -
            // counts as stale, and the recorded clk_tck/uptime values
            // in the report show why.
            val fresh = tck > 0 && minStartSec >= 0.0 &&
                startTicks / tck.toDouble() >= minStartSec - 1.0
            if (fresh) timeOnly.add(VirglTestCommon.ProcObs(pid, rest[0], rest[19], uid)) else stale++
        }
        return ShellScan(owned, timeOnly, ancUnknown, stale, unreadable, foreign, cmdUnreadable)
    }

    @Test fun virglGnomeSessionQualification(): Unit = runBlocking {
        val d = Distros.UBUNTU
        val report = File(ctx.filesDir, "virgl-session.txt")
        val xcontent = File(ctx.filesDir, "virgl-session-xcontent.img")
        val xcontentPartial = File(ctx.filesDir, "virgl-session-xcontent.partial")
        val out = StringBuilder()
        // Clear stale artifacts: an unreached stage must never leave a
        // previous run's image or summary looking current.
        report.delete(); xcontent.delete(); xcontentPartial.delete()
        // The census is THIS arm's artifact: delete any stale copy so an
        // unreached stage can never present a previous run's census as
        // current. (phantom-snapshots.log/-raw.log are append-only across
        // arms BY DESIGN - in the full lane the harness arm's samples
        // precede this arm; deleting them here would destroy that
        // evidence. This arm's samples are delimited by labeled boundary
        // beats instead.)
        val censusFile = File(ctx.filesDir, "ldoa-session-census.txt")
        censusFile.delete()
        val savedFlag = if (flag.isFile) flag.readText() else null
        // E1 experiment mode is EXPLICIT: only the instrumentation arg
        // ldoaE1=1 (dispatch input, never a default) opts this launch in.
        // With the arg absent a STALE flag is removed so an earlier
        // experiment run can never silently turn a stock run into E1.
        // The mode is reported in the verdict file; flags are restored
        // in finally with INDEPENDENT attempts. Run 41343293710 (E1 off)
        // is the stock baseline for the count/roster/trim comparison.
        val e1Requested = InstrumentationRegistry.getArguments().getString("ldoaE1") == "1"
        val e1Flag = File(ctx.filesDir, ".ldoa-session-diet-e1")
        val savedE1Flag = if (e1Flag.isFile) e1Flag.readText() else null
        // VTEST-MC EVALUATION OPT-IN (explicit -e ldoaVtestMc 1 ONLY;
        // default OFF = stock fork-per-client server, byte-identical).
        // Flag file checked by XServer.launchOrReuseGnomeSession
        // (DEBUG-only + regular non-symlink there). Exact NIO exception
        // semantics here - NOT the inherited unchecked delete() pattern:
        // a pre-existing flag that is not a regular non-symlink file is
        // REFUSED (unexpected types are never managed); regular content
        // is preserved byte-exactly for the finally restore; every
        // write/delete below throws on failure (fail closed).
        val vtestMcRequested = InstrumentationRegistry.getArguments().getString("ldoaVtestMc") == "1"
        val vtestMcFlag = File(ctx.filesDir, "vtest-mc.optin")
        val vtestMcPath = vtestMcFlag.toPath()
        val savedVtestMcFlag: ByteArray? =
            if (Files.exists(vtestMcPath, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(vtestMcPath, LinkOption.NOFOLLOW_LINKS))
                    throw IllegalStateException("vtest-mc.optin exists but is not a regular " +
                        "non-symlink file - refusing to manage an unexpected type (fail closed)")
                Files.readAllBytes(vtestMcPath)
            } else null
        // VIRGL DEBUG LOGGING OPT-IN (explicit -e ldoaVirglDebug 1 ONLY;
        // default OFF = byte-identical launch env on both sides). One flag
        // file gates BOTH the server traces (XServer: VIRGL_LOG_LEVEL +
        // VREND_DEBUG=feat extraEnv) and the guest init-time GL logs
        // (GuestUbuntu: LIBGL_DEBUG=verbose + EGL_LOG_LEVEL=debug;
        // MUTTER_DEBUG defaults are NOT modified). Strict lifecycle via
        // LdoaFlagLifecycle (JVM-fixture-covered), NOT the Files.exists
        // pattern.
        val virglDebugRequested = InstrumentationRegistry.getArguments().getString("ldoaVirglDebug") == "1"
        val virglDebugFlag = File(ctx.filesDir, ".ldoa-virgl-debug")
        val virglDebugPath = virglDebugFlag.toPath()
        // LdoaFlagLifecycle (JVM-fixture-covered): absence is null; a
        // non-regular/symlinked path throws; access failure propagates -
        // unknown never reads as "absent".
        val savedVirglDebugFlag: ByteArray? = LdoaFlagLifecycle.readIfRegular(virglDebugPath)
        // INVOCATION-LEVEL ownership: restored only if THIS invocation's
        // own successful mutation says so (NONE touches nothing - a
        // same-bytes lookalike from a racer is never deleted).
        var virglDebugMutation = LdoaFlagLifecycle.Mutation.NONE
        var executed = false
        var checksPassed = false
        // Arm-scoped launch generation markers, hoisted so the finally
        // block can scope the E1 outcome to THIS arm's launch (never the
        // whole append-log: old E1 lines are not the current outcome).
        var launchBase = -1L
        var launchGenId: String? = null
        // BYTE base of files/virgl-server.log BEFORE this arm's launch:
        // the product appends every generation's server output to that
        // one file, so only bytes at/after this offset belong to THIS
        // arm's server (the same binding discipline as launchBase).
        var virglLogBase = -1L
        // THIS launch's generation-unique server socket path, captured
        // at launch: the ownership discriminator for the post-stop
        // residual roster (an unrelated own-uid vtest process is never
        // attributed to this launch and never signaled).
        var armSocketPath: String? = null
        var rendererVerdict = "UNKNOWN (stage not reached)"
        var presentationVerdict = "REVIEWER-VERIFICATION PENDING (stage not reached)"
        var failure: Throwable? = null
        val uiDevice = androidx.test.uiautomator.UiDevice.getInstance(
            InstrumentationRegistry.getInstrumentation())
        // Sampler lifecycle lives OUTSIDE try so finally can stop it even
        // when the body throws before the thread started.
        val samplerStop = java.util.concurrent.atomic.AtomicBoolean(false)
        var samplerThread: Thread? = null
        try {
            val installer = DistroInstaller(ctx)
            assertTrue("ubuntu not installed - full-lane autoenter setup must precede this test",
                installer.isInstalled(d))
            val rootfs = installer.rootfsDir(d)
            assertTrue("gnome not provisioned - full-lane autoenter setup must precede this test",
                GuestUbuntu.hasGnome(rootfs))

            // Warmup + reset BEFORE the opt-in flag is set, so the
            // warmup's tracked default-renderer launch can never
            // contaminate this test's virgl evidence world.
            SessionReset.trackedWarmup(ctx, d, rootfs) { m -> Log.i(TAG, "warmup: " + m) }
            out.append("pre-launch reset: ")
                .append(SessionReset.resetForFreshLaunch(ctx, d) { m -> Log.i(TAG, m) }).append('\n')

            // M3 census, PRE-LAUNCH: written incrementally to disk
            // BEFORE the observed trim window (partial file + TRUNCATED
            // marker on failure; a stale census was deleted at arm
            // start). Auxiliary evidence: a census failure is recorded,
            // never fails the arm.
            try {
                SessionCensus.writeCensus(rootfs, censusFile, "ubuntu") { cmd ->
                    try { uiDevice.executeShellCommand(cmd) } catch (t: Throwable) { null }
                }
                out.append("session census: written pre-launch (ldoa-session-census.txt)\n")
            } catch (t: Throwable) {
                out.append("session census WRITE FAILED (auxiliary evidence only): ")
                    .append(t.message ?: t.javaClass.simpleName).append('\n')
            }

            // Bounded phantom-sampler LIFETIME for this arm: the same
            // shape the harness arm uses (daemon thread, 5s cadence,
            // hard end bound inside the 1500s arm bound), started before
            // launch, stopped and joined in finally. Coverage label:
            // point beats every 5s from pre-launch to teardown; the
            // dumpsys global section is 15s-cadence-gated and may SKIP
            // beats, and its "All Active App Child Processes" section
            // can be absent on a given build (recorded as unavailable -
            // global counts are NOT promised). Each sample spawns one
            // transient marker process, recorded in the sample itself.
            try {
                PhantomSampler.sample(uiDevice, "virgl-session-arm-start")
            } catch (t: Throwable) {
                out.append("sampler arm-start beat FAILED (auxiliary evidence only): ")
                    .append(t.message ?: t.javaClass.simpleName).append('\n')
            }
            val samplerEnd = System.currentTimeMillis() + 780_000
            samplerThread = kotlin.concurrent.thread(name = "virgl-session-sampler", isDaemon = true) {
                var n = 0
                while (!samplerStop.get() && System.currentTimeMillis() < samplerEnd) {
                    try {
                        PhantomSampler.sample(uiDevice, "virgl-session-periodic-" + n)
                    } catch (t: Throwable) {
                        Log.i(TAG, "sampler periodic-" + n + " failed: " + (t.message ?: t.javaClass.simpleName))
                    }
                    n++
                    Thread.sleep(5_000)
                }
            }

            // Opt IN for THIS launch only; flags are restored in finally
            // (independent attempts). E1 only when explicitly requested.
            flag.writeText("1")
            if (vtestMcRequested) {
                Files.write(vtestMcPath, byteArrayOf(0x31)) // "1"; NIO throws on failure
                out.append("vtest-mc evaluation mode: ON (explicit instrumentation arg\n")
                    .append("  ldoaVtestMc=1; opt-in --no-fork --multi-clients single-process\n")
                    .append("  multi-client server UNDER EVALUATION - not a default)\n")
            } else {
                if (savedVtestMcFlag != null) {
                    Files.delete(vtestMcPath) // NIO throws on failure (fail closed)
                    out.append("vtest-mc evaluation mode: OFF (no ldoaVtestMc arg; removed a\n")
                        .append("  STALE vtest-mc flag so this launch is the default fork mode)\n")
                } else {
                    out.append("vtest-mc evaluation mode: OFF (no ldoaVtestMc arg; default fork mode)\n")
                }
            }
            if (virglDebugRequested) {
                if (savedVirglDebugFlag == null) {
                    // explicit WRITE+CREATE_NEW+NOFOLLOW: fail loudly on a
                    // race or symlink; never truncates whatever appeared.
                    LdoaFlagLifecycle.createNew(virglDebugPath, byteArrayOf(0x31))
                    virglDebugMutation = LdoaFlagLifecycle.Mutation.CREATED
                } // else: pre-existing regular flag left UNCHANGED (no rewrite,
                  // no owned mutation; restore will touch nothing)
                out.append("virgl-debug logging mode: ON (explicit instrumentation arg\n")
                    .append("  ldoaVirglDebug=1; MINIMAL INIT-ONLY set: server VREND_DEBUG=feat +\n")
                    .append("  VIRGL_LOG_LEVEL=debug (stderr, flushed per message), guest\n")
                    .append("  LIBGL_DEBUG=verbose + EGL_LOG_LEVEL=debug (init-time driver-load\n")
                    .append("  lines only; MUTTER_DEBUG defaults UNCHANGED - no per-frame topics;\n")
                    .append("  knob presence strings-verified on the noble 25.2.8 Mesa binaries;\n")
                    .append("  diagnostic launch ONLY)\n")
            } else {
                if (savedVirglDebugFlag != null) {
                    // Owned stale-flag removal ONLY while the bytes are
                    // still the saved ones; a same-position newcomer is
                    // preserved and this invocation records NO mutation.
                    if (LdoaFlagLifecycle.deleteIfUnchanged(virglDebugPath, savedVirglDebugFlag)) {
                        virglDebugMutation = LdoaFlagLifecycle.Mutation.DELETED
                        out.append("virgl-debug logging mode: OFF (no ldoaVirglDebug arg; removed a\n")
                            .append("  STALE virgl-debug flag so this launch is the default env)\n")
                    } else {
                        out.append("virgl-debug logging mode: OFF REQUESTED but stale flag content\n")
                            .append("  changed before removal - PRESERVED, no owned mutation; the\n")
                            .append("  pre-launch mode check below REFUSES this launch (no silent\n")
                            .append("  logging-on launch under a requested OFF)\n")
                    }
                } else {
                    out.append("virgl-debug logging mode: OFF (no ldoaVirglDebug arg; default env)\n")
                }
            }
            // Fail closed BEFORE launch: the EFFECTIVE flag state must
            // equal the requested mode - a preserved-foreign or racer flag
            // must never silently launch with logging in the wrong state.
            LdoaFlagLifecycle.requireMode(virglDebugPath, virglDebugRequested)
            if (e1Requested) {
                e1Flag.writeText("1")
                out.append("E1 experiment mode: ON (explicit instrumentation arg ldoaE1=1;\n")
                    .append("  Wwan+Smartcard autostart masks expected ACTIVE this launch)\n")
            } else {
                if (e1Flag.isFile) {
                    e1Flag.delete()
                    out.append("E1 experiment mode: OFF (no ldoaE1 arg; removed a STALE E1 flag so\n")
                        .append("  this launch is stock)\n")
                } else {
                    out.append("E1 experiment mode: OFF (no ldoaE1 arg; stock launch)\n")
                }
            }

            XServer.start(ctx)
            // CHAR base from one content snapshot BEFORE launch (never
            // File.length() bytes) + the launch-moment uptime for the
            // freshness candidate check (runtime clk_tck, no USER_HZ).
            val base = (if (logFile.isFile) logFile.readText() else "").length.toLong()
            launchBase = base
            virglLogBase = run {
                val vf = File(ctx.filesDir, "virgl-server.log")
                if (vf.isFile) vf.length() else 0L
            }
            // Boot-relative uptime from the Android runtime - NEVER
            // /proc/uptime (EACCES for apps at targetSdk 34; that read
            // aborted this test's first CI execution before launch).
            // Units: SECONDS since boot, the same clock domain as the
            // /proc stat starttime the freshness check divides by the
            // runtime clk_tck. -1.0 = unavailable: freshness becomes
            // unverifiable and candidates degrade to stale (recorded).
            val launchUptimeSec = try {
                android.os.SystemClock.elapsedRealtime() / 1000.0
            } catch (t: Throwable) { -1.0 }
            val tck = clkTck()
            out.append("clk_tck=").append(tck).append(" ticks/sec (runtime sysconf); uptimeSec=")
                .append(launchUptimeSec.toLong())
                .append(" (SystemClock.elapsedRealtime, seconds since boot; -1 = unavailable)\n")
            XServer.launchOrReuseGnomeSession(ctx, d, rootfs)
            val launchId = XServer.currentLaunchIdForTest()
            launchGenId = launchId
            assertNotNull("current launch identity unavailable - cannot bind evidence to THIS launch " +
                "(failing clearly, not scanning an unbound window)", launchId)

            // The launch MUST have started its own render server: a missing
            // identity means a software fallback and every virgl verdict is
            // void - fail loudly, never infer from readiness.
            val srvIdent = XServer.virglServerIdentForTest()
            assertNotNull("opted-in session launch started NO virgl render server " +
                "(software fallback - virgl session verdict void)", srvIdent)
            val sock = XServer.virglCurrentSocketForTest()
            armSocketPath = sock?.absolutePath
            out.append("render-server: pid=").append(srvIdent!!.pid).append(" start=").append(srvIdent.start)
                .append(" uid=").append(srvIdent.uid).append(" sock=")
                .append(sock?.absolutePath ?: "?").append(" launchId=").append(launchId).append('\n')

            // VTEST-MC EFFECTIVE MODE vs REQUESTED MODE, proven on the
            // EXACT OWNED RUNNING server: the launch-captured identity
            // (pid + /proc starttime + uid) is re-verified live BEFORE
            // and AFTER the cmdline read (a reused/gone pid voids the
            // attribution), then the running process's own args are
            // matched against the requested mode. String presence in
            // the packaged binary is NOT proof - only the live cmdline
            // of the identity-verified server is.
            run {
                val srvPid = srvIdent!!.pid
                fun liveStart(p: Int): String? {
                    val st = try { File("/proc/" + p + "/stat").readText() } catch (t: Throwable) { return null }
                    val close = st.lastIndexOf(')')
                    if (close < 0) return null
                    val rest = st.substring(close + 1).trim().split(Regex("\\s+"))
                    return if (rest.size < 20) null else rest[19]
                }
                val startBefore = liveStart(srvPid)
                val cmdArgs = try {
                    String(File("/proc/" + srvPid + "/cmdline").readBytes())
                        .split(0.toChar()).filter { a -> a.isNotEmpty() }
                } catch (t: Throwable) { emptyList() }
                val startAfter = liveStart(srvPid)
                val uidNow = try { android.system.Os.stat("/proc/" + srvPid).st_uid } catch (t: Throwable) { -1 }
                val identOk = startBefore != null && startBefore == srvIdent!!.start &&
                    startAfter == startBefore && uidNow == srvIdent.uid
                val isServerBin = cmdArgs.isNotEmpty() && cmdArgs[0].endsWith("libvirgl_test_server.so")
                val effNoFork = cmdArgs.contains("--no-fork")
                val effMulti = cmdArgs.contains("--multi-clients")
                out.append("== VTEST-MC effective-mode: requested=")
                    .append(if (vtestMcRequested) "ON" else "OFF")
                    .append(" effective-args=")
                    .append(if (cmdArgs.isEmpty()) "UNREADABLE" else cmdArgs.drop(1).joinToString(" "))
                    .append(" identity=").append(if (identOk) "REVERIFIED-LIVE" else "MISMATCH-OR-GONE")
                    .append(" server-binary=").append(if (isServerBin) "CONFIRMED" else "UNCONFIRMED")
                    .append('\n')
                assertTrue("vtest-mc effective-args attribution failed: the pid captured at launch " +
                    "no longer holds the launch identity (reused/gone) or is not the vtest server " +
                    "binary - cannot prove which mode the running server is in (fail closed)",
                    identOk && isServerBin)
                assertTrue("vtest-mc mode mismatch: requested=" + (if (vtestMcRequested) "ON" else "OFF") +
                    " but the RUNNING server's effective args are [" + cmdArgs.drop(1).joinToString(" ") +
                    "] - a silently-ignored opt-in or stale mode must never masquerade as the requested mode",
                    if (vtestMcRequested) effNoFork && effMulti else !effNoFork && !effMulti)
                // OWNED roster: actual children of the server (ppid
                // scan of own-uid procs). Fork-mode workers ARE the
                // live-client evidence; under --no-fork ANY child
                // contradicts the fork path (vtest_server.c:548) being
                // disabled. A partial scan = UNKNOWN, never zero.
                val roster = scanOwnUidProcs()
                val workers = roster.first.filter { it.second == srvPid }
                out.append("== VTEST-MC roster: server=1 workers=")
                    .append(if (roster.second) "UNKNOWN(own-uid scan partial - completeness not established)"
                        else workers.size.toString())
                    .append('\n')
                if (!roster.second) {
                    assertTrue("vtest-mc --no-fork roster violation: " + workers.size +
                        " child process(es) parented by server pid " + srvPid +
                        " - the fork-per-client path is active despite --no-fork, so the " +
                        "effective mode is not what the args claim",
                        !vtestMcRequested || workers.isEmpty())
                }
            }

            // EARLY ancestry: exact ppid startup chains of this uid's
            // processes seconds after spawn - the M3 census's runtime
            // half, captured before the observed trim window.
            try {
                SessionCensus.appendAncestrySnapshot(
                    File(ctx.filesDir, "ldoa-session-census.txt"), "post-launch-early")
                out.append("ancestry snapshot: post-launch-early appended to census\n")
            } catch (t: Throwable) {
                out.append("ancestry snapshot FAILED (auxiliary evidence only): ")
                    .append(t.message ?: t.javaClass.simpleName).append('\n')
            }

            // READINESS: the LATEST state line inside THIS launch's
            // generation slice decides; an earlier alive never masks a
            // later failure; rotation fails closed.
            val deadline = System.currentTimeMillis() + 600_000
            var ready = false
            var died: String? = null
            var aliveLine: String? = null
            while (System.currentTimeMillis() < deadline) {
                val w = window(base)
                    ?: throw AssertionError("guest-gnome.log rotated/shrank under launch " + launchId +
                        " - marker binding is void (fail-closed)")
                val slice = generationSlice(w, launchId!!)
                if (slice != null) {
                    val states = slice.lineSequence().filter {
                        it.contains("gnome-session alive") || it.contains("session launcher died") ||
                        it.contains("falling back to bare gnome-shell")
                    }.toList()
                    val last = states.lastOrNull()
                    if (last != null && last.contains("gnome-session alive")) {
                        // The guest's OWN failure output is not launcher-
                        // stamped; as the LATEST content after the last
                        // alive inside this generation it still voids it.
                        val afterAlive = slice.substring(slice.lastIndexOf(last))
                        if (afterAlive.contains("Oh no! Something has gone wrong") ||
                            afterAlive.contains("Cannot open display")) {
                            died = "guest failure output AFTER alive marker in this generation"
                            break
                        }
                        ready = true; aliveLine = last.trim(); break
                    }
                    if (last != null) { died = last.trim(); break }
                    if (slice.contains("Oh no! Something has gone wrong") ||
                        slice.contains("Cannot open display")) {
                        died = "guest failure output in generation slice"; break
                    }
                }
                Thread.sleep(5_000)
            }
            out.append("VERDICT readiness: ")
                .append(if (ready) "PASS (latest state of this generation = alive)"
                        else "FAIL (" + (died ?: "10min window expired") + ")").append('\n')

            // Second ancestry/env point, POST-READINESS: the immediate
            // post-launch snapshot can precede the gnome-session spawn.
            // Auxiliary evidence: recorded, never fails the arm.
            try {
                SessionCensus.appendAncestrySnapshot(censusFile, "post-readiness")
                out.append("ancestry snapshot: post-readiness appended to census\n")
            } catch (t: Throwable) {
                out.append("ancestry snapshot post-readiness FAILED (auxiliary evidence only): ")
                    .append(t.message ?: t.javaClass.simpleName).append('\n')
            }
            assertTrue("READINESS failed - latest state of THIS generation never reached alive " +
                "(window tail: " + (window(base)?.takeLast(400) ?: "<rotated>") + ")", ready)
            val gsPid = aliveLine!!.substringAfter("gnome-session alive pid=")
                .takeWhile { it.isDigit() }.toInt()
            // Session OS-identity for later revalidation (guest capture
            // gate): /proc starttime captured NOW; a later /proc/PID match
            // with a different starttime means PID reuse, not the session.
            val gsStartTick = procStartTick(gsPid)
            assertTrue("session process " + gsPid + " starttime unreadable at capture point " +
                "(later evidence could not be bound to this session's OS identity)",
                gsStartTick != null)

            // COMPOSITOR evidence, owned-by-this-generation only.
            val scan = findGnomeShell(gsPid, launchUptimeSec, tck)
            out.append("gnome-shell scan: owned-by-ancestry(to gnome-session pid ").append(gsPid)
                .append(")=").append(scan.owned.joinToString(",") { it.pid.toString() + "/" + it.state })
                .append(" time-only-candidates(NOT owned, freshness via runtime clk_tck only)=")
                .append(scan.timeOnlyCandidates.joinToString(",") { it.pid.toString() + "/" + it.state })
                .append(" ancestry-unknown=").append(scan.ancestryUnknown)
                .append(" stale-generation=").append(scan.staleGeneration)
                .append(" unreadable=").append(scan.unreadable)
                .append(" foreign-uid=").append(scan.foreignUid)
                .append(" cmdline-unreadable=").append(scan.cmdlineUnreadable).append('\n')
            if (scan.unreadable > 0 || scan.cmdlineUnreadable > 0 || scan.ancestryUnknown > 0) {
                out.append("WARNING: discovery PARTIAL (counts above - an unreadable/unwalkable entry")
                    .append(" could be the shell; these are COUNTED, not silently dropped)\n")
            }
            assertTrue("no gnome-shell process OWNED by this session generation found " +
                "(time-only candidates=" + scan.timeOnlyCandidates.size +
                " ancestry-unknown=" + scan.ancestryUnknown + " stale=" + scan.staleGeneration +
                " unreadable=" + scan.unreadable + " cmdline-unreadable=" + scan.cmdlineUnreadable + ")",
                scan.owned.isNotEmpty())
            var envProven = 0
            for (p in scan.owned) {
                val env = try {
                    File("/proc/" + p.pid + "/environ").readBytes()
                        .toString(Charsets.UTF_8).split(0.toChar())
                } catch (t: Throwable) {
                    out.append("shell ").append(p.pid).append(" environ read FAILED: ")
                        .append(t.message).append('\n')
                    null
                }
                // Revalidate identity AROUND the environ read: exit+reuse
                // between scan and read must not attribute a stranger.
                val still = VirglTestCommon.obsAs(p.pid, p.start, p.uid)
                if (still.verdict != VirglTestCommon.ObsVerdict.ALIVE) {
                    out.append("shell ").append(p.pid).append(" identity changed around environ read (")
                        .append(still.verdict).append(") - discarded as churn\n")
                    continue
                }
                val hasVirgl = env?.contains("GALLIUM_DRIVER=virpipe") == true
                if (hasVirgl) envProven++
                out.append("shell ").append(p.pid).append(" environ GALLIUM_DRIVER=virpipe: ")
                    .append(if (env == null) "UNKNOWN(read-failed)" else hasVirgl.toString()).append('\n')
                // Delivery evidence ONLY (not renderer proof): the two env
                // vars that steer cogl/clutter driver selection. Reported
                // enum-or-OTHER from the same already-read, already-identity-
                // revalidated environ bytes; no new /proc read.
                out.append("shell ").append(p.pid).append(" environ COGL_DRIVER: ")
                    .append(EnvTokenEvidence.envEnumToken(env, "COGL_DRIVER", EnvTokenEvidence.COGL_DRIVER_TOKENS, caseInsensitive = true)).append('\n')
                out.append("shell ").append(p.pid).append(" environ CLUTTER_DRIVER: ")
                    .append(EnvTokenEvidence.envEnumToken(env, "CLUTTER_DRIVER", EnvTokenEvidence.CLUTTER_DRIVER_TOKENS, caseInsensitive = false)).append('\n')
                // fd-to-inode correlation: THIS process's socket inodes vs
                // net/unix rows. A bare name row is namespace table
                // content, never attribution.
                val sockInodes = HashSet<String>()
                var fdError: String? = null
                try {
                    File("/proc/" + p.pid + "/fd").listFiles()?.forEach { fd ->
                        val target = try { android.system.Os.readlink(fd.absolutePath) }
                                     catch (t: Throwable) { null }
                        if (target != null && target.startsWith("socket:[")) {
                            sockInodes.add(target.removePrefix("socket:[").removeSuffix("]"))
                        }
                    }
                } catch (t: Throwable) { fdError = t.message }
                val unix = try { File("/proc/" + p.pid + "/net/unix").readText() } catch (t: Throwable) { null }
                val correlated = if (unix != null && sock != null) {
                    unix.lineSequence().any { line ->
                        val cols = line.trim().split(Regex("\\s+"))
                        cols.size >= 8 && cols[6] in sockInodes && cols.last() == sock.absolutePath
                    }
                } else false
                out.append("shell ").append(p.pid).append(" vtest-socket fd correlation: ")
                    .append(if (correlated) "FD-INODE-CORRELATED to " + sock!!.absolutePath
                            else "UNKNOWN (" + (if (fdError != null) "fd scan failed: " + fdError
                                else if (unix == null) "net/unix unreadable (EACCES on this emulator)"
                                else "no fd-to-inode match") + ")").append('\n')
            }
            val w2 = window(base)
                ?: throw AssertionError("guest-gnome.log rotated mid-test (fail-closed)")
            val slice2 = generationSlice(w2, launchId!!)
                ?: throw AssertionError("this launch's id line vanished mid-test (fail-closed)")
            // Keyword-filtered renderer lines are CANDIDATES, retained RAW:
            // log attribution to gnome-shell's actual GL context is NOT
            // authenticated, so they never upgrade the renderer verdict.
            val rendLines = slice2.lineSequence()
                .filter { it.contains("renderer", ignoreCase = true) &&
                    (it.contains("shell", ignoreCase = true) || it.contains("mutter", ignoreCase = true) ||
                     it.contains("clutter", ignoreCase = true)) }
                .take(5).toList()
            out.append("session-log renderer-keyword CANDIDATE lines (RAW, this generation only;")
                .append(" keyword filter = NOT authenticated process attribution): ")
                .append(if (rendLines.isEmpty()) "NONE" else rendLines.joinToString(" | ").take(600))
                .append('\n')
            rendererVerdict = "UNKNOWN (no evidence authenticated to the compositor's actual GL" +
                " context; " + rendLines.size + " raw candidate line(s) retained above, never" +
                " auto-upgraded)"
            out.append("VERDICT compositor-configured-route: ")
                .append(if (envProven > 0) "PROVEN on " + envProven + "/" + scan.owned.size +
                    " ancestry-owned this-generation gnome-shell process(es) (LAUNCHED on the virgl" +
                    " route; says nothing about the actual context or displayed pixels)"
                        else "NOT-PROVEN (no owned this-generation gnome-shell environ carried" +
                    " GALLIUM_DRIVER=virpipe)")
                .append('\n')
            // VTEST-MC SERVER-LOG OBSERVATION (pinned 1.3.0 protocol;
            // NEVER a gate): vtest_server.c:217 prints the multi-clients
            // startup line when the flag parses, :759 "client context
            // created." per new client context, :538 "client: ..." on
            // disconnect. ALL of these go to STDOUT (printf /
            // fprintf(stdout)), which is block-buffered to a file, and
            // the identity-verified teardown SIGNALS the server, so
            // buffered lines can be lost before any flush (run
            // 41365593840 aborted on exactly this: the line was ABSENT
            // while the live cmdline PROVED the flag effective). The
            // hard mode proof is the live-cmdline + roster gate above;
            // everything here is a recorded observation with its
            // readable state and byte accounting.
            run {
                val vf = File(ctx.filesDir, "virgl-server.log")
                val read = try {
                    if (vf.isFile) Pair(vf.readBytes(), "ok") else Pair(ByteArray(0), "absent")
                } catch (t: Throwable) {
                    Pair(ByteArray(0), "unreadable(" + (t.message ?: t.javaClass.simpleName) + ")")
                }
                val all = read.first
                val bound = virglLogBase >= 0 && all.size.toLong() >= virglLogBase
                val region = if (bound)
                    String(all, virglLogBase.toInt(), all.size - virglLogBase.toInt()) else ""
                val mcLine = region.contains("multi-clients enabled: clients must trust each other")
                var created = 0
                var gone = 0
                var bal = 0
                var maxLive = 0
                for (ln in region.lines()) {
                    if (ln.contains("client context created.")) {
                        created++
                        if (bal + 1 > maxLive) maxLive = bal + 1
                        bal++
                    } else if (ln.startsWith("client: ")) {
                        gone++
                        bal--
                    }
                }
                out.append("== VTEST-MC server-log OBSERVATION (not a gate; stdout is block-buffered\n")
                    .append("  and the teardown signals the server, so buffered lines can be lost before flush):\n")
                    .append("  read-state=").append(read.second)
                    .append(" byte-base=").append(virglLogBase)
                    .append(" current-bytes=").append(all.size)
                    .append(if (bound) "" else " BASE-INVALID(file shrank or base unset - slice void)")
                    .append(" slice-bytes=").append(if (bound) region.length else 0).append('\n')
                    .append("  multi-clients-startup-line=").append(if (mcLine) "PRESENT" else "ABSENT")
                    .append(" client-contexts-created=").append(created)
                    .append(" client-disconnects=").append(gone)
                    .append(" (lower bounds under buffer loss; ABSENT proves nothing)\n")
                out.append("== VTEST-MC concurrency: UNPROVEN (unpaired running-balance max-live=")
                    .append(maxLive)
                    .append(" is only a protocol-derived candidate - missing or disordered disconnect\n")
                    .append("  lines can INFLATE it, and the pinned 1.3.0 log lines carry no client\n")
                    .append("  identity to pair, so simultaneous clients are never asserted from this evidence)\n")
            }
            out.append("VERDICT compositor-renderer: ").append(rendererVerdict).append('\n')
            assertTrue("owned this-generation gnome-shell environ lacks GALLIUM_DRIVER=virpipe - " +
                "compositor was not launched on the virgl route", envProven > 0)

            // GUEST X-CONTENT: guest-side image capture, gated on SESSION
            // IDENTITY ONLY (Android focus/presentation is NOT a
            // prerequisite): current launch generation AND the session
            // process REVALIDATED by /proc starttime against the value
            // recorded at readiness, before AND after capture - a bare
            // /proc/PID directory does not exclude PID reuse. Gate or
            // binding failures are RECORDED (capture skipped / artifact
            // discarded) and never skip the Android presentation
            // diagnostics below. Bounds (asset script): EVERY tool call
            // runs under `timeout` because the runGuest timeout only
            // SIGKILLs proot itself and ORPHANS the guest-side tool
            // (Proot.kt bounded path) - the in-guest per-tool budget is
            // the only reliable bound, and without coreutils timeout the
            // script refuses to invoke ANY tool; unique per-launch
            // charset-checked path; confirmed stale removal over exact
            // allowlisted paths (dangling symlinks included); symlink or
            // non-regular outputs refused UNREAD; accepted images are
            // 1KiB..64MiB regular files; rejected/failed output is
            // retained as BOUNDED 256KiB excerpts with original size +
            // sha256. A nonblank image is NOT automatically a desktop -
            // reviewer pixel inspection decides.
            var guestVerdict: String
            val genPreCap = XServer.currentLaunchIdForTest()
            val gateFailure: String? = when {
                genPreCap != launchId -> "generation changed (was " + launchId + " now " + genPreCap + ")"
                !File("/proc/" + gsPid).isDirectory -> "session process " + gsPid + " gone"
                !gsStartMatches(gsPid, gsStartTick) -> "session process " + gsPid +
                    " starttime mismatch vs readiness capture (PID reuse suspected)"
                else -> null
            }
            if (gateFailure != null) {
                out.append("VERDICT guest-xcontent: GATE-FAILED fail-closed, capture NOT attempted: ")
                    .append(gateFailure).append('\n')
            } else {
                val xcapScript = "ldoa-guest-xcapture-" + launchId + ".sh"
                val xcapBase = "/tmp/ldoa-xcap-" + launchId
                File(rootfs, "tmp/" + xcapScript).writeText(
                    InstrumentationRegistry.getInstrumentation().context.assets
                        .open("ldoa-guest-xcapture.sh").bufferedReader().readText())
                try {
                    val xcapInvokeAt = System.currentTimeMillis()
                    val xcap = Proot.runGuest(ctx, rootfs,
                        listOf("/bin/sh", "/tmp/" + xcapScript, xcapBase),
                        extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"),
                        extraEnv = mapOf("DISPLAY" to ":0"), timeoutMs = 90_000)
                    out.append("guest-xcontent probe transcript (RAW, bounded): ")
                        .append(xcap.stdout.trim().replace('\n', ' ').take(800)).append('\n')
                    val xcapResult = xcap.stdout.lineSequence()
                        .lastOrNull { it.startsWith("XC_RESULT ") } ?: ""
                    val xcapTool = Regex("tool=([a-z0-9-]+)").find(xcapResult)?.groupValues?.get(1) ?: ""
                    val xcapOut = Regex("out=(\\S+)").find(xcapResult)?.groupValues?.get(1) ?: ""
                    // Rejected/failed tool output: bounded per-tool
                    // excerpts (each <=256KiB, at most 4 tools) are
                    // CONCATENATED with origin headers into the partial
                    // artifact, so failed output survives even when a
                    // later fallback succeeds. The copy is free-space
                    // guarded and never unbounded.
                    val partialNotes = mutableListOf<String>()
                    val partOut = java.io.ByteArrayOutputStream()
                    for (line in xcap.stdout.lineSequence()) {
                        if (!line.startsWith("XC_ATTEMPT")) continue
                        val pPath = Regex("partial=(\\S+)").find(line)?.groupValues?.get(1) ?: continue
                        val pTool = Regex("tool=([a-z0-9-]+)").find(line)?.groupValues?.get(1) ?: "unknown"
                        val pOrig = Regex("origbytes=(\\d+)").find(line)?.groupValues?.get(1) ?: "?"
                        val pSha = Regex("sha256=(\\S+)").find(line)?.groupValues?.get(1) ?: "?"
                        val pf = File(rootfs, pPath.removePrefix("/"))
                        if (pf.isFile && pf.length() <= 262_144) {
                            partOut.write(("== PARTIAL tool=" + pTool + " origbytes=" + pOrig +
                                " sha256=" + pSha + " guest-path=" + pPath +
                                " excerpt-bytes=" + pf.length() + " ==\n").toByteArray())
                            partOut.write(pf.readBytes())
                            partOut.write(10) // newline byte
                            partialNotes.add(pTool + ":" + pOrig + "B:sha256-" + pSha.take(12))
                        } else {
                            partialNotes.add(pTool + ":OMITTED(guest excerpt missing or over the 256KiB bound)")
                        }
                    }
                    if (partOut.size() > 0) {
                        if (partOut.size() * 2L < ctx.filesDir.usableSpace) {
                            val partDst = File(ctx.filesDir, "virgl-session-xcontent.partial")
                            partDst.delete(); partDst.writeBytes(partOut.toByteArray())
                        } else {
                            partialNotes.add("partial-copy-skipped:insufficient-free-space")
                        }
                    }
                    guestVerdict = if (xcap.exitCode == 0 &&
                        xcapResult.startsWith("XC_RESULT tool=") && xcapOut.isNotEmpty()) {
                        val capSrc = File(rootfs, xcapOut.removePrefix("/"))
                        val bindFailure: String? = when {
                            !capSrc.isFile -> "output missing or not a regular file"
                            capSrc.length() <= 1000 -> "output trivial (" + capSrc.length() + " bytes)"
                            capSrc.length() > 67_108_864 -> "output over the 64MiB bound (" + capSrc.length() + " bytes)"
                            capSrc.lastModified() < xcapInvokeAt - 5_000 ->
                                "output predates this invocation (stale bound as fresh): mtime=" +
                                    capSrc.lastModified() + " invokedAt=" + xcapInvokeAt
                            capSrc.length() * 2 > ctx.filesDir.usableSpace ->
                                "insufficient filesDir free space for a bounded copy"
                            else -> null
                        }
                        if (bindFailure != null) {
                            "CAPTURE-INVALID (" + bindFailure + "); partials=[" +
                                partialNotes.joinToString(",") + "] - recorded, NEVER bound as guest content"
                        } else {
                            val capDst = File(ctx.filesDir, "virgl-session-xcontent.img")
                            capDst.delete(); capSrc.copyTo(capDst, overwrite = true)
                            // Encoded-byte diversity ONLY: distinct 4KiB
                            // blocks of the ENCODED bytes. Compressed
                            // formats defeat block-level reading; this is
                            // NOT pixel-uniformity evidence.
                            val capBytes = capDst.readBytes()
                            val blocks = HashSet<Int>()
                            var bi = 0
                            while (bi < capBytes.size) {
                                blocks.add(capBytes.copyOfRange(bi, minOf(bi + 4096, capBytes.size)).contentHashCode())
                                bi += 4096
                            }
                            "CAPTURED tool=" + xcapTool + " bytes=" + capDst.length() +
                                " format=" + (if (xcapOut.endsWith(".png")) "png" else "xwd") +
                                " distinct-4KiB-encoded-blocks=" + blocks.size + "/" + (capBytes.size + 4095) / 4096 +
                                " (encoded-byte diversity ONLY, NOT pixel uniformity); partials=[" +
                                partialNotes.joinToString(",") + "]; artifact virgl-session-xcontent.img" +
                                " REQUIRES reviewer pixel inspection - a nonblank scene is not" +
                                " automatically a desktop"
                        }
                    } else {
                        "NOT-CAPTURED exit=" + xcap.exitCode + " result=[" + xcapResult.take(200) +
                            "] stderr=[" + xcap.stderr.take(200) + "] partials=[" +
                            partialNotes.joinToString(",") + "] (concatenated bounded excerpts in" +
                            " virgl-session-xcontent.partial when non-empty; guest X content" +
                            " UNMEASURED this run - on-screen pixels say nothing about guest-side" +
                            " X content either way)"
                    }
                    // POST-capture identity revalidation: if the generation
                    // or the session starttime moved during capture, the
                    // artifact cannot be bound to THIS launch - discard.
                    if (XServer.currentLaunchIdForTest() != launchId || !gsStartMatches(gsPid, gsStartTick)) {
                        File(ctx.filesDir, "virgl-session-xcontent.img").delete()
                        guestVerdict = "IDENTITY-LOST-DURING-CAPTURE (generation or session starttime" +
                            " changed; artifact DISCARDED, never bound to this launch); prior verdict: " +
                            guestVerdict
                    }
                    out.append("VERDICT guest-xcontent: ").append(guestVerdict).append('\n')
                } finally {
                    // Confirmed cleanup over the EXACT allowlisted guest
                    // paths (script CLEANUP mode), then the script itself;
                    // both attempts recorded, neither masks the verdict.
                    try {
                        val xcapClean = Proot.runGuest(ctx, rootfs,
                            listOf("/bin/sh", "/tmp/" + xcapScript, "CLEANUP", xcapBase),
                            extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"),
                            extraEnv = mapOf("DISPLAY" to ":0"), timeoutMs = 30_000)
                        if (xcapClean.exitCode != 0)
                            out.append("guest-tmp cleanup UNCONFIRMED (leftovers are device-side evidence only): ")
                                .append(xcapClean.stdout.trim().take(200)).append('\n')
                    } catch (t: Throwable) {
                        out.append("guest-tmp cleanup attempt FAILED (auxiliary): ")
                            .append(t.message ?: t.javaClass.simpleName).append('\n')
                    }
                    try {
                        val scrRm = Proot.runGuest(ctx, rootfs,
                            listOf("/bin/sh", "-c",
                                "rm -f /tmp/" + xcapScript + " && test ! -e /tmp/" + xcapScript),
                            timeoutMs = 30_000)
                        if (scrRm.exitCode != 0)
                            out.append("guest capture script removal unconfirmed: exit=")
                                .append(scrRm.exitCode).append('\n')
                    } catch (t: Throwable) {
                        out.append("guest capture script removal attempt FAILED (auxiliary): ")
                            .append(t.message ?: t.javaClass.simpleName).append('\n')
                    }
                }
            }

            // WINDOW TOPOLOGY + BOUNDED ROOT PIXEL probes (xmap /
            // xmap-pixels): bounded SEQUENTIAL samples before the
            // screencap, then a FRESH final-sample immediately before
            // the shot (xcontrol settle/final-sample pattern). TOPOLOGY:
            // a viewable window can be blank; unmapped windows prove
            // nothing about overlay or root rendering; NO content or
            // presentation discrimination is claimed from map state.
            // PIXELS: bounded root XGetImage patches from the CI-built
            // helper; black-or-failed patches are NOT proof of absent
            // guest content, and root GetImage is NOT the exact
            // compositor scanout or renderer texture (source header +
            // ci/xmap-pixels/BUILD.md). Probes and screencap are
            // SEQUENTIAL: timestamps and order are recorded, never a
            // same-instant claim. Generation and process identity are
            // re-checked before and after; a change discards the sample
            // as STALE rather than reading across generations. A probe
            // FAILURE never skips Android-side evidence (the screencap
            // still runs); only the stale-owner gate skips probes.
            val xmapGate: String? = when {
                XServer.currentLaunchIdForTest() != launchId ->
                    "generation changed before topology probe"
                !gsStartMatches(gsPid, gsStartTick) ->
                    "session process generation changed before topology probe"
                else -> null
            }
            if (xmapGate != null) {
                out.append("VERDICT window-topology: SKIPPED fail-closed: ")
                    .append(xmapGate).append('\n')
                out.append("VERDICT window-pixels: SKIPPED fail-closed (same stale-owner gate)\n")
                out.append("VERDICT overlay-pixels: SKIPPED fail-closed (same stale-owner gate)\n")
            } else {
                val xmapScript = "ldoa-guest-xmap-" + launchId + ".sh"
                val xmapBase = "/tmp/ldoa-xmap-" + launchId
                File(rootfs, "tmp/" + xmapScript).writeText(
                    InstrumentationRegistry.getInstrumentation().context.assets
                        .open("ldoa-guest-xmap.sh").bufferedReader().readText())
                try {
                    try {
                        val xmapT0 = System.currentTimeMillis()
                        val xmap = Proot.runGuest(ctx, rootfs,
                            listOf("/bin/sh", "/tmp/" + xmapScript, xmapBase),
                            extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"),
                            extraEnv = mapOf("DISPLAY" to ":0"), timeoutMs = 45_000)
                        val xmapT1 = System.currentTimeMillis()
                        out.append("window-topology probe (order 1 of 4: topology, pixels, overlay,")
                            .append(" screencap; sequential ms timestamps start=").append(xmapT0)
                            .append(" end=").append(xmapT1)
                            .append(" launchId=").append(launchId)
                            .append(" gsPid=").append(gsPid).append("):\n")
                        xmap.stdout.lineSequence().filter { it.startsWith("XM_") }
                            .forEach { out.append("  ").append(it).append('\n') }
                        val toolLine = xmap.stdout.lineSequence()
                            .firstOrNull { it.startsWith("XM_TOOL ") } ?: ""
                        val statsLines = xmap.stdout.lineSequence()
                            .filter { it.startsWith("XM_STATS ") }.toList()
                        val viewable = statsLines.count { it.contains("mapstate=IsViewable") }
                        val topologyVerdict = when {
                            XServer.currentLaunchIdForTest() != launchId ||
                                !gsStartMatches(gsPid, gsStartTick) ->
                                "STALE (generation changed during probe; sample discarded," +
                                    " never read across generations)"
                            toolLine.isEmpty() -> "NO-RECORD (probe produced no tool line)"
                            toolLine.endsWith("absent") ->
                                "NO-RECORD (xwininfo absent - topology leg missing, not zero-content)"
                            statsLines.isEmpty() ->
                                "NO-RECORD (no window stats parsed; see raw XM_ lines)"
                            else -> "topology sampled: " + viewable + " of " + statsLines.size +
                                " probed windows IsViewable (TOPOLOGY ONLY: viewable != drawn" +
                                " != presented; viewable blank windows and overlay/root" +
                                " rendering outside the sample remain possible)"
                        }
                        out.append("VERDICT window-topology: ").append(topologyVerdict).append('\n')
                    } catch (t: Throwable) {
                        out.append("VERDICT window-topology: NO-RECORD (probe threw ")
                            .append(t.javaClass.simpleName).append(": ")
                            .append(t.message ?: "no-message")
                            .append("; Android-side evidence continues)\n")
                    }

                    // STEP 2 of 3: bounded root pixel sample. The helper
                    // is CI-built on the ubuntu-24.04 runner (noble ABI;
                    // ci/xmap-pixels/BUILD.md) and adb-pushed to
                    // /data/local/tmp/ldoa-xmap-pixels; the CI log
                    // prints its runner-side sha256 and the device-side
                    // sha256 is recorded here (recorded pair for
                    // reviewer comparison, not a gate).
                    val pxHelper = "ldoa-xmap-pixels-" + launchId
                    val pxPpmRel = "tmp/ldoa-xmap-" + launchId + ".ppm"
                    val devHelper = File("/data/local/tmp/ldoa-xmap-pixels")
                    if (!devHelper.isFile) {
                        out.append("VERDICT window-pixels: NO-RECORD (helper not delivered to" +
                            " /data/local/tmp - CI build/push failed or non-CI run; see" +
                            " XMAP-PIXELS-BUILD lines in the lane log)\n")
                    } else {
                        try {
                            // Staleness BEFORE invocation: any previous
                            // PPM/helper at these launch-unique paths is
                            // removed AND confirmed gone; a survivor
                            // (or symlink) refuses the sample.
                            removeOwnedGuestFile(rootfs, pxPpmRel)
                            removeOwnedGuestFile(rootfs, "tmp/" + pxHelper)
                            val prePpm = File(rootfs, pxPpmRel)
                            val preHelper = File(rootfs, "tmp/" + pxHelper)
                            if (prePpm.exists() || java.nio.file.Files.isSymbolicLink(prePpm.toPath()) ||
                                preHelper.exists() || java.nio.file.Files.isSymbolicLink(preHelper.toPath())) {
                                out.append("VERDICT window-pixels: NO-RECORD (stale/symlink artifact" +
                                    " path survived pre-invocation removal; refusing to sample)\n")
                            } else {
                            val pxSha = sha256Hex(devHelper)
                            File(rootfs, "tmp/" + pxHelper).writeBytes(devHelper.readBytes())
                            File(rootfs, "tmp/" + pxHelper).setExecutable(true, false)
                            val pxT0 = System.currentTimeMillis()
                            val px = Proot.runGuest(ctx, rootfs,
                                listOf("/bin/sh", "-c",
                                    "ldconfig -p 2>/dev/null | grep -i libX11 | sed 's/^/XP_LDCONFIG /';" +
                                        " exec /tmp/" + pxHelper + " /tmp/" + pxPpmRel.removePrefix("tmp/")),
                                extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"),
                                extraEnv = mapOf("DISPLAY" to ":0"), timeoutMs = 30_000)
                            val pxT1 = System.currentTimeMillis()
                            out.append("window-pixels probe (order 2 of 4; sequential ms timestamps")
                                .append(" start=").append(pxT0).append(" end=").append(pxT1)
                                .append(" helper-device-sha256=").append(pxSha)
                                .append(" exit=").append(px.exitCode).append("):\n")
                            px.stdout.lineSequence().filter { it.startsWith("XP_") }
                                .forEach { out.append("  ").append(it).append('\n') }
                            val ppmGuest = File(rootfs, pxPpmRel)
                            val resultLine = px.stdout.lineSequence()
                                .firstOrNull { it.startsWith("XP_RESULT ") } ?: ""
                            val patchesOk = Regex("""patches-ok=(\d+)""").find(resultLine)
                                ?.groupValues?.get(1)?.toIntOrNull() ?: -1
                            // Exit taxonomy: XP_RESULT alone NEVER accepts
                            // output. 0 = full sample; 7 = PARTIAL sample
                            // (valid patches logged, never full success);
                            // 2/3/4/5/6/8 = NO-RECORD with the helper's
                            // own stage; anything else unexpected. The
                            // artifact must ALSO pass freshness/symlink/
                            // header/dims/body validation.
                            val ppmReject = validateXmapPpm(ppmGuest, pxT0)
                            val pixelsVerdict = when {
                                XServer.currentLaunchIdForTest() != launchId ||
                                    !gsStartMatches(gsPid, gsStartTick) ->
                                    "STALE (generation changed during probe; sample discarded," +
                                        " never read across generations)"
                                px.exitCode == 6 ->
                                    "NO-RECORD (helper deadline hit; see XP_ERROR stage lines)"
                                px.exitCode == 2 || px.exitCode == 3 || px.exitCode == 4 ||
                                    px.exitCode == 5 || px.exitCode == 8 ->
                                    "NO-RECORD (helper exit " + px.exitCode +
                                        "; see XP_ERROR/XP_XERROR stage lines)"
                                px.exitCode != 0 && px.exitCode != 7 ->
                                    "NO-RECORD (unexpected helper exit " + px.exitCode + ")"
                                resultLine.isEmpty() ->
                                    "NO-RECORD (no XP_RESULT line at exit " + px.exitCode + ")"
                                ppmReject != null ->
                                    "NO-RECORD (artifact rejected: " + ppmReject + ")"
                                px.exitCode == 7 -> {
                                    File(ctx.filesDir, "virgl-session-xmap.ppm")
                                        .writeBytes(ppmGuest.readBytes())
                                    "SAMPLED-PARTIAL (exit 7: " + patchesOk + " of 5 patches valid;" +
                                        " PARTIAL sample - valid patches logged, NOT full success;" +
                                        " black-or-failed is NOT proof of absent content; NOT the" +
                                        " compositor scanout; artifact virgl-session-xmap.ppm" +
                                        " header-gated CI-side; reviewer pixel inspection required)"
                                }
                                else -> {
                                    File(ctx.filesDir, "virgl-session-xmap.ppm")
                                        .writeBytes(ppmGuest.readBytes())
                                    "SAMPLED (5 of 5 patches; black-or-failed is NOT proof of" +
                                        " absent content; NOT the compositor scanout; artifact" +
                                        " virgl-session-xmap.ppm header-gated CI-side; reviewer pixel" +
                                        " inspection still required)"
                                }
                            }
                            out.append("VERDICT window-pixels: ").append(pixelsVerdict).append('\n')
                            }
                        } catch (t: Throwable) {
                            out.append("VERDICT window-pixels: NO-RECORD (probe threw ")
                                .append(t.javaClass.simpleName).append(": ")
                                .append(t.message ?: "no-message")
                                .append("; Android-side evidence continues)\n")
                        } finally {
                            out.append("guest pixel-helper removal: ")
                                .append(removeOwnedGuestFile(rootfs, "tmp/" + pxHelper)).append('\n')
                            out.append("guest pixel-ppm removal: ")
                                .append(removeOwnedGuestFile(rootfs, pxPpmRel)).append('\n')
                        }

                        // STEP 3 of 4: bounded COMPOSITE OVERLAY pixel sample
                        // (xmap-overlay; ci/xmap-overlay/BUILD.md). Tiered:
                        // Composite version + _NET_WM_CM_S0 selection owner are
                        // READ-ONLY; XCompositeGetOverlayWindow is an
                        // acquisition+mapping operation (it can create/map the
                        // overlay window), issued ONLY when an owner exists,
                        // with the owner re-read immediately before and after
                        // and every acquisition matched by release+close on
                        // all paths. The CM owner is a current selection state,
                        // NOT an authenticated compositor producer; producer
                        // attribution is always UNKNOWN (XC-MISC GetXIDRange is
                        // an allocation range, not a resource mask; no XID
                        // partition is assumed).
                        // Post-get mapstate is never evidence of preexisting
                        // viewability. Overlay patches are sampled drawable
                        // content: NOT the Android-presented frame; black-or-
                        // failed is NOT proof of absent content. The artifact
                        // virgl-session-xov.ppm is SEPARATE from the root
                        // virgl-session-xmap.ppm with its own retention check.
                        val ovHelper = "ldoa-xmap-overlay-" + launchId
                        val ovPpmRel = "tmp/ldoa-xov-" + launchId + ".ppm"
                        val devOvPath = java.nio.file.Paths.get("/data/local/tmp/ldoa-xmap-overlay")
                        if (!java.nio.file.Files.isRegularFile(devOvPath,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                            out.append("VERDICT overlay-pixels: NO-RECORD (helper unavailable or" +
                                " not verified as a regular file at /data/local/tmp (NIO" +
                                " NOFOLLOW check; absence is not asserted) - CI build/push" +
                                " failed or non-CI run; see XMAP-OVERLAY-BUILD lines in the" +
                                " lane log)\n")
                        } else {
                            try {
                                removeOwnedGuestFile(rootfs, ovPpmRel)
                                removeOwnedGuestFile(rootfs, "tmp/" + ovHelper)
                                val preOvPpm = File(rootfs, ovPpmRel).toPath()
                                val preOvHelper = File(rootfs, "tmp/" + ovHelper).toPath()
                                val preOvStateUnknown = listOf(preOvPpm, preOvHelper).any { pre ->
                                    try {
                                        java.nio.file.Files.readAttributes(pre,
                                            java.nio.file.attribute.BasicFileAttributes::class.java,
                                            java.nio.file.LinkOption.NOFOLLOW_LINKS)
                                        true
                                    } catch (e: java.nio.file.NoSuchFileException) {
                                        false
                                    } catch (e: java.io.IOException) {
                                        true
                                    }
                                }
                                if (preOvStateUnknown) {
                                    out.append("VERDICT overlay-pixels: NO-RECORD (artifact/helper path" +
                                        " present or access state UNKNOWN after removal" +
                                        " (readAttributes NOFOLLOW; only NoSuchFileException proves" +
                                        " absence); refusing to sample)\n")
                                } else {
                                val ovSha = sha256Hex(devOvPath.toFile())
                                // CREATE_NEW + NOFOLLOW: creation fails
                                // (FileAlreadyExistsException) rather than following a link
                                // raced in after the absence gate.
                                java.nio.file.Files.write(preOvHelper,
                                    devOvPath.toFile().readBytes(),
                                    java.nio.file.StandardOpenOption.CREATE_NEW,
                                    java.nio.file.StandardOpenOption.WRITE,
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS)
                                File(rootfs, "tmp/" + ovHelper).setExecutable(true, false)
                                val ovCopyAttrs = java.nio.file.Files.readAttributes(preOvHelper,
                                    java.nio.file.attribute.BasicFileAttributes::class.java,
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS)
                                if (!ovCopyAttrs.isRegularFile ||
                                    sha256Hex(File(rootfs, "tmp/" + ovHelper)) != ovSha) {
                                    out.append("VERDICT overlay-pixels: NO-RECORD (guest helper copy" +
                                        " not verified (NOFOLLOW regular-file check and/or sha256" +
                                        " mismatch against /data/local/tmp original);" +
                                        " refusing to invoke)\n")
                                } else {
                                val ovT0 = System.currentTimeMillis()
                                val ov = Proot.runGuest(ctx, rootfs,
                                    listOf("/bin/sh", "-c",
                                        "ldconfig -p 2>/dev/null | grep -i 'libXcomposite' |" +
                                            " sed 's/^/XO_LDCONFIG /';" +
                                            " if ! ldconfig -p 2>/dev/null | grep -q 'libXcomposite\\.so'; then" +
                                            " echo 'XO_SKIPPED reason=guest-libs-absent'; exit 10; fi;" +
                                            " exec /tmp/" + ovHelper + " /tmp/" +
                                            ovPpmRel.removePrefix("tmp/")),
                                    extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"),
                                    extraEnv = mapOf("DISPLAY" to ":0"), timeoutMs = 30_000)
                                val ovT1 = System.currentTimeMillis()
                                out.append("overlay-pixels probe (order 3 of 4; sequential ms timestamps")
                                    .append(" start=").append(ovT0).append(" end=").append(ovT1)
                                    .append(" helper-device-sha256=").append(ovSha)
                                    .append(" exit=").append(ov.exitCode).append("):\n")
                                ov.stdout.lineSequence().filter { it.startsWith("XO_") }
                                    .forEach { out.append("  ").append(it).append('\n') }
                                val ovPpmGuest = File(rootfs, ovPpmRel)
                                val ovResultLine = ov.stdout.lineSequence()
                                    .firstOrNull { it.startsWith("XO_RESULT ") } ?: ""
                                val ovPatchesOk = Regex("""patches-ok=(\d+)""").find(ovResultLine)
                                    ?.groupValues?.get(1)?.toIntOrNull() ?: -1
                                val ovSkipLine = ov.stdout.lineSequence()
                                    .firstOrNull { it.startsWith("XO_SKIPPED ") } ?: ""
                                // Exit taxonomy mirrors window-pixels, PLUS 10 =
                                // SKIPPED (recorded outcome: no Composite ext, no
                                // CM owner, owner lost, or guest libs absent; no
                                // overlay request is issued in those states).
                                val ovPpmReject = validateXmapPpm(ovPpmGuest, ovT0)
                                val overlayVerdict = when {
                                    XServer.currentLaunchIdForTest() != launchId ||
                                        !gsStartMatches(gsPid, gsStartTick) ->
                                        "STALE (generation changed during probe; sample discarded," +
                                            " never read across generations)"
                                    ov.exitCode == 6 ->
                                        "NO-RECORD (helper deadline hit; see XO_ERROR stage lines)"
                                    ov.exitCode == 10 ->
                                        "SKIPPED (recorded outcome: " +
                                            (if (ovSkipLine.isNotEmpty()) ovSkipLine.removePrefix("XO_SKIPPED ")
                                             else ovResultLine.removePrefix("XO_RESULT ")) +
                                            "; root-only fallback preserved; no overlay request is" +
                                            " issued in skipped states (no Composite extension, no" +
                                            " CM atom, owner None, owner lost, or guest libs absent)"
                                    ov.exitCode == 2 || ov.exitCode == 3 || ov.exitCode == 4 ||
                                        ov.exitCode == 5 || ov.exitCode == 8 ->
                                        "NO-RECORD (helper exit " + ov.exitCode +
                                            "; see XO_ERROR/XO_XERROR stage lines)"
                                    ov.exitCode != 0 && ov.exitCode != 7 ->
                                        "NO-RECORD (unexpected helper exit " + ov.exitCode + ")"
                                    ovResultLine.isEmpty() ->
                                        "NO-RECORD (no XO_RESULT line at exit " + ov.exitCode + ")"
                                    ovPpmReject != null ->
                                        "NO-RECORD (artifact rejected: " + ovPpmReject + ")"
                                    ov.exitCode == 7 -> {
                                        File(ctx.filesDir, "virgl-session-xov.ppm")
                                            .writeBytes(ovPpmGuest.readBytes())
                                        "SAMPLED-PARTIAL (exit 7: " + ovPatchesOk + " of 5 patches valid;" +
                                            " PARTIAL sample - valid patches logged, NOT full success;" +
                                            " sampled composite-overlay drawable content per XO_ATTRIB" +
                                            " attribution only; NOT the Android-presented frame;" +
                                            " black-or-failed is NOT proof of absent content; artifact" +
                                            " virgl-session-xov.ppm header-gated CI-side; reviewer" +
                                            " pixel inspection required)"
                                    }
                                    else -> {
                                        File(ctx.filesDir, "virgl-session-xov.ppm")
                                            .writeBytes(ovPpmGuest.readBytes())
                                        "SAMPLED (5 of 5 patches; sampled composite-overlay drawable" +
                                            " content per XO_ATTRIB attribution only; NOT the" +
                                            " Android-presented frame; black-or-failed is NOT proof" +
                                            " of absent content; artifact virgl-session-xov.ppm" +
                                            " header-gated CI-side; reviewer pixel inspection still" +
                                            " required)"
                                    }
                                }
                                out.append("VERDICT overlay-pixels: ").append(overlayVerdict).append('\n')
                                }
                                }
                            } catch (t: Throwable) {
                                out.append("VERDICT overlay-pixels: NO-RECORD (probe threw ")
                                    .append(t.javaClass.simpleName).append(": ")
                                    .append(t.message ?: "no-message")
                                    .append("; Android-side evidence continues)\n")
                            } finally {
                                out.append("guest overlay-helper removal: ")
                                    .append(removeOwnedGuestFile(rootfs, "tmp/" + ovHelper)).append('\n')
                                out.append("guest overlay-ppm removal: ")
                                    .append(removeOwnedGuestFile(rootfs, ovPpmRel)).append('\n')
                            }
                        }
                    }
                } finally {
                    out.append("guest xmap script removal: ")
                        .append(removeOwnedGuestFile(rootfs, "tmp/" + xmapScript)).append('\n')
                }
            }

            // ANDROID PRESENTATION: STRICT gate UNCHANGED - shot pipeline
            // gated on capture-target IDENTITY (component/surface/content
            // axes + CURRENT-state resample; at most ONE verified narrow
            // dismissal per arm; a recurrence ends focus polling early WITH
            // EVIDENCE instead of manufacturing focus).
            // ORDERING (run 41368404492): the guest topology/pixel probes
            // above now run BEFORE this Android focus stage - they are
            // generation/starttime-owned guest diagnostics that never
            // depend on Android focus, so a persistent ANR obstruction
            // fails presentation honestly HERE while the guest samples
            // are still retained. The identity gate itself is NOT relaxed.
            val establishAt = System.currentTimeMillis()
            val ident = establishDesktopCaptureIdentity(launchId!!, rootfs, gsPid)
            out.append("capture-identity: ").append(ident.summary).append('\n')
            assertTrue("capture-target identity NOT established - " + ident.failure, ident.ok)
            // FINAL SAMPLE immediately before the screencap (xcontrol
            // pattern): focus NOW + generation NOW + session process
            // NOW. The full establish (ANR dismissal budget) is NOT
            // re-run - this is the current-state resample only, and a
            // lapse here means the pending capture can no longer be
            // bound to the established target: fail-closed.
            val finalDevice = androidx.test.uiautomator.UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation())
            val finalFocusAt = System.currentTimeMillis()
            val finalFocus = currentFocusState(finalDevice)
            val finalGenOk = XServer.currentLaunchIdForTest() == launchId
            val finalGsOk = gsStartMatches(gsPid, gsStartTick)
            // CURRENT renderer display binding (own PID, entries since
            // the establish phase): the latest own rendererSetWindow
            // must still be a NONZERO bind - a post-bind teardown
            // (handle 0 / zero dims) yields EMPTY and fails closed.
            val finalBindAt = System.currentTimeMillis()
            val finalBind = latestOwnRendererBind(
                finalDevice.executeShellCommand("logcat -d -v threadtime -t 800 -s gles-renderer:D"),
                android.os.Process.myPid(), establishAt)
            out.append("final-sample pre-shot (after topology/pixels, immediately before")
                .append(" screencap): focusSampleAt=").append(finalFocusAt)
                .append(" bindSampleAt=").append(finalBindAt)
                .append(" focusNow=").append(finalFocus.state)
                .append(" generationOk=").append(finalGenOk)
                .append(" sessionProcOk=").append(finalGsOk)
                .append(" bindNow=").append(
                    if (finalBind.isEmpty()) "EMPTY(latest own entry is teardown or none since establish)"
                    else "[" + finalBind.take(200) + "]").append('\n')
            assertTrue("capture-target identity LAPSED before screencap (focusNow=" +
                finalFocus.state + " generationOk=" + finalGenOk + " sessionProcOk=" + finalGsOk +
                " bindNowEmpty=" + finalBind.isEmpty() + ")",
                finalFocus.state == FocusState.FOCUSED && finalGenOk && finalGsOk &&
                    finalBind.isNotEmpty())
            val shotAt = System.currentTimeMillis()
            Shot.save("virgl-session-desktop")
            out.append("screencap-order: 4 of 4, requested at ").append(shotAt)
                .append(" (sequential after topology + pixel probes; no same-instant claim)\n")
            presentationVerdict = "capture via Android-display screencap (shot pipeline, name" +
                " virgl-session-desktop); capture-target identity ESTABLISHED [" + ident.summary + "];" +
                " artifact arrival GATED CI-side; REVIEWER-VERIFIED presentation: PENDING" +
                " (identity evidence targets component/display/surface/session-windows;" +
                " the artifact image must STILL be inspected for the intended X desktop" +
                " content - identity is not pixel review)"
            out.append("VERDICT presentation: ").append(presentationVerdict).append('\n')

            // STOP-CLEANUP on the SESSION's render server.
            val sscan = VirglTestCommon.scanChildren(srvIdent.pid)
            val workers = (sscan.viaChildrenFile + sscan.viaPpidScan)
                .filter { it.state != "Z" }.distinctBy { it.pid }
            out.append("session-server workers at stop: ")
                .append(if (workers.isEmpty()) "NONE-OBSERVED"
                        else workers.joinToString(",") { it.pid.toString() + "/" + it.state })
                .append(" children-file-error: ").append(sscan.childrenFileError ?: "none")
                .append(" ppid-scan-error: ").append(sscan.scanError ?: "none").append('\n')
            val stopT0 = System.nanoTime()
            XServer.stopVirglServerForTest("virgl-session-stop")
            val stopRetNanos = System.nanoTime()
            val sampleT0 = System.nanoTime()
            val samples = workers.map { w ->
                val rd0 = System.nanoTime()
                val o = VirglTestCommon.obsAs(w.pid, w.start, w.uid)
                w.pid.toString() + "/" + o.verdict +
                    (if (o.state != null) "(" + o.state + ")" else "") +
                    (if (o.note != null) "[" + o.note + "]" else "") +
                    "{readMs=" + (System.nanoTime() - rd0) / 1_000_000 + "}"
            }
            out.append("stop-return-wallMs ").append((stopRetNanos - stopT0) / 1_000_000)
                .append(" worker-state-at-stop-return(sample-start-latencyMs ")
                .append((sampleT0 - stopRetNanos) / 1_000_000)
                .append(" sample-windowMs ").append((System.nanoTime() - sampleT0) / 1_000_000)
                .append("): ").append(samples).append('\n')
            val post = VirglTestCommon.obsAs(srvIdent.pid, srvIdent.start, srvIdent.uid)
            assertTrue("session render server not gone as captured identity after stop (verdict=" +
                post.verdict + ")",
                post.verdict == VirglTestCommon.ObsVerdict.GONE || post.state == "Z")
            for (w in workers) {
                val o = VirglTestCommon.obsAs(w.pid, w.start, w.uid)
                assertTrue("session worker " + w.pid + " not gone after stop (verdict=" + o.verdict + ")",
                    o.verdict == VirglTestCommon.ObsVerdict.GONE || o.state == "Z")
            }
            out.append("VERDICT stop-cleanup: PASS (server + ").append(workers.size)
                .append(" worker(s) gone as captured identities; emulator, this session class only)\n")

            out.append("SMOOTHNESS: NOT MEASURED - excluded from this qualification\n")
            out.append("emulator result - NOT Adreno; NOT a performance or smoothness verdict\n")
            executed = true
            checksPassed = true
        } catch (t: Throwable) {
            // Keep the diagnostic evidence with the partial report.
            out.append("== BODY-FAILURE: ").append(t.message ?: t.toString()).append('\n')
            failure = t
        } finally {
            // Stop the bounded sampler BEFORE anything else mutates the
            // process world; join is bounded (a stuck beat never hangs
            // teardown), then one terminal labeled beat records the
            // pre-teardown world. Auxiliary: failures recorded, never
            // fail the arm.
            try {
                samplerStop.set(true)
                samplerThread?.join(15_000)
                val stillAlive = samplerThread?.isAlive == true
                if (stillAlive) {
                    // join(15s) is a WAIT bound, not cancellation: a beat
                    // stuck inside executeShellCommand (no timeout in that
                    // API) keeps running. The thread is NOT labeled
                    // stopped; its lifetime is INCOMPLETE/UNKNOWN; the
                    // final beat is SKIPPED because two writers must
                    // never share phantom-snapshots.log. Backstops: the
                    // thread is a daemon (never blocks JVM exit) and the
                    // arm's outer 1500s instrument bound kills the
                    // process.
                    out.append("sampler thread STILL ALIVE after 15s join bound (likely stuck in" +
                        " an executeShellCommand beat) - lifetime INCOMPLETE/UNKNOWN, final beat" +
                        " skipped (no concurrent writers); daemon thread + outer instrument" +
                        " bound are the backstops\n")
                } else {
                    PhantomSampler.sample(uiDevice, "virgl-session-final")
                }
            } catch (t: Throwable) {
                out.append("sampler stop/final FAILED (auxiliary evidence only): ")
                    .append(t.message ?: t.javaClass.simpleName).append('\n')
            }
            // Independent attempts - teardown, report persistence, flag
            // restore - each failure FAILS the test (original preserved,
            // later ones suppressed onto it); none is swallowed into a
            // green suite.
            try {
                XServer.stopVirglServerForTest("virgl-session-cleanup")
                SessionReset.resetForFreshLaunch(ctx, d) { m -> Log.i(TAG, "teardown: " + m) }
            } catch (ce: Throwable) {
                out.append("== CLEANUP-ERROR teardown: ").append(ce.message ?: ce.toString()).append('\n')
                if (failure == null) failure = ce else failure!!.addSuppressed(ce)
            }
            // POST-STOP OWNED ROSTER: count own-uid processes whose
            // live cmdline carries THIS launch's generation-unique
            // socket path (ownership attribution; an unrelated own-uid
            // server is never attributed to this launch and NEVER
            // signaled - this check signals NOTHING). Zero workers
            // alone proves NOTHING about rendering or crash isolation -
            // this checks ONLY teardown completeness. A partial scan
            // (any unreadable own-uid cmdline) or a missing socket
            // identity = UNKNOWN, never "cleanup complete".
            try {
                val sockPath = armSocketPath
                if (sockPath == null) {
                    out.append("post-stop virgl roster: UNKNOWN (no owned-server socket identity captured this arm)\n")
                } else {
                    val resid = scanOwnUidVirglForSocket(sockPath)
                    out.append("post-stop virgl roster: ")
                        .append(if (resid.third)
                            "UNKNOWN (own-uid scan partial - a residual owned by this launch cannot be excluded)"
                            else if (resid.first.isEmpty())
                                "zero residual virgl processes owned by THIS launch (socket-path attributed)"
                            else "RESIDUAL " + resid.first.size + " virgl process(es) owned by THIS launch: " +
                                resid.first.joinToString(","))
                        .append(if (resid.second > 0)
                            " [other own-uid virgl processes NOT this launch's: " + resid.second +
                                " - recorded, never attributed, never signaled]" else "")
                        .append('\n')
                    if (!resid.third && resid.first.isNotEmpty())
                        throw java.io.IOException("post-stop residual virgl processes owned by this launch: " +
                            resid.first.joinToString(","))
                }
            } catch (re: Throwable) {
                out.append("== CLEANUP-ERROR post-stop virgl roster: ").append(re.message ?: re.toString()).append('\n')
                if (failure == null) failure = re else failure!!.addSuppressed(re)
            }
            // Flag restores happen BEFORE the report write so their
            // outcome actually reaches the verdict file. INDEPENDENT
            // attempts: a failure restoring one never skips the other;
            // both failures are preserved (second suppressed onto the
            // first). A delete() that returns false while the file still
            // exists IS a failure. NOTE: restoring the HOST flag only
            // gates the NEXT launch - it does NOT remove guest masks from
            // a session that already ran (guest-side rollback happens at
            // the next launch's script, manifest-verified).
            try {
                if (savedFlag != null) flag.writeText(savedFlag)
                else if (!flag.delete() && flag.isFile)
                    throw java.io.IOException("virgl flag delete() returned false and the file still exists")
            } catch (fe: Throwable) {
                out.append("== CLEANUP-ERROR virgl-flag restore: ").append(fe.message ?: fe.toString()).append('\n')
                if (failure == null) failure = fe else failure!!.addSuppressed(fe)
            }
            try {
                if (savedE1Flag != null) e1Flag.writeText(savedE1Flag)
                else if (!e1Flag.delete() && e1Flag.isFile)
                    throw java.io.IOException("e1 flag delete() returned false and the file still exists")
            } catch (fe: Throwable) {
                out.append("== CLEANUP-ERROR e1-flag restore: ").append(fe.message ?: fe.toString()).append('\n')
                if (failure == null) failure = fe else failure!!.addSuppressed(fe)
            }
            try {
                // Exact NIO restore, independent attempt: saved regular
                // content restored byte-exactly; an unexpected type is
                // REFUSED (never overwritten or deleted); every failure
                // throws (fail closed) and is preserved as a
                // CLEANUP-ERROR like the other flag restores.
                if (savedVtestMcFlag != null) {
                    if (Files.exists(vtestMcPath, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isRegularFile(vtestMcPath, LinkOption.NOFOLLOW_LINKS))
                        throw java.io.IOException("vtest-mc.optin became a non-regular file during " +
                            "the test - refusing to overwrite an unexpected type (fail closed)")
                    Files.write(vtestMcPath, savedVtestMcFlag)
                } else if (Files.exists(vtestMcPath, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(vtestMcPath, LinkOption.NOFOLLOW_LINKS))
                        throw java.io.IOException("vtest-mc.optin restore found a non-regular " +
                            "file - refusing to delete an unexpected type (fail closed)")
                    Files.delete(vtestMcPath)
                }
            } catch (fe: Throwable) {
                out.append("== CLEANUP-ERROR vtest-mc-flag restore: ").append(fe.message ?: fe.toString()).append('\n')
                if (failure == null) failure = fe else failure!!.addSuppressed(fe)
            }
            try {
                // Undo ONLY this invocation's own recorded mutation:
                // NONE touches nothing (a same-bytes lookalike is never
                // deleted); CREATED deletes only its exact bytes; DELETED
                // recreates the saved bytes via CREATE_NEW (a newcomer
                // fails loudly instead of being overwritten).
                val outcome = LdoaFlagLifecycle.guardedRestore(
                    virglDebugPath, savedVirglDebugFlag, virglDebugMutation)
                if (outcome == LdoaFlagLifecycle.RestoreOutcome.PRESERVED_FOREIGN)
                    out.append("== CLEANUP-WARNING virgl-debug-flag: content changed during the " +
                        "test - foreign/replaced file PRESERVED untouched (restore skipped)\n")
            } catch (fe: Throwable) {
                out.append("== CLEANUP-ERROR virgl-debug-flag restore: ").append(fe.message ?: fe.toString()).append('\n')
                if (failure == null) failure = fe else failure!!.addSuppressed(fe)
            }
            // Runtime mode + guest-side mask/rollback outcome, scoped to
            // THIS arm's launch generation (window(launchBase) +
            // generationSlice(launchGenId) - the same binding the
            // readiness gate uses). The whole append-log is NEVER read
            // for this: old E1 lines are not the current outcome. Mode ON
            // alone is NOT evidence masks installed - only per-mask
            // ACTIVE lines inside this generation count; rotation fails
            // closed (UNREADABLE, never silently absent).
            try {
                out.append("== E1 MODE: ").append(if (e1Requested) "ON (arg ldoaE1=1)"
                           else "OFF (no ldoaE1 arg)").append('\n')
                val w = if (launchBase >= 0) window(launchBase) else null
                val slice = if (w != null && launchGenId != null) generationSlice(w, launchGenId) else null
                if (launchBase < 0 || launchGenId == null) {
                    out.append("== E1 GUEST OUTCOME: NO-LAUNCH this arm (no generation exists)\n")
                } else if (slice == null) {
                    out.append("== E1 GUEST OUTCOME: UNREADABLE - guest log rotated / generation" +
                        " not found (fail-closed; NOT evidence of absence)\n")
                } else {
                    val e1Lines = slice.lineSequence()
                        .filter { it.contains("gnome-launch: E1") }.toList()
                    if (e1Lines.isEmpty()) {
                        out.append("== E1 GUEST OUTCOME: no E1 lines in THIS launch generation")
                            .append(if (e1Requested) " (UNEXPECTED with mode ON - masks NOT proven" +
                                    " installed; experiment coverage UNKNOWN)\n"
                                    else " (expected for a stock launch)\n")
                    } else {
                        out.append("== E1 GUEST OUTCOME this generation (").append(e1Lines.size.toString())
                            .append(" lines, verbatim, max 12):\n")
                        e1Lines.take(12).forEach { out.append("   ").append(it).append('\n') }
                    }
                }
            } catch (t: Throwable) {
                out.append("== E1 GUEST OUTCOME: read failed (auxiliary): ")
                    .append(t.message ?: t.javaClass.simpleName).append('\n')
            }
            try {
                out.append(if (executed) "== EXECUTION: complete\n"
                           else "== EXECUTION: ABORTED - partial results above ==\n")
                out.append(if (checksPassed) "== CHECKS: all executed checks passed\n"
                           else "== CHECKS: FAILED or incomplete ==\n")
                out.append("== COMPOSITOR-RENDERER: ").append(rendererVerdict).append('\n')
                out.append("== PRESENTATION: ").append(presentationVerdict).append('\n')
                report.writeText(out.toString())
            } catch (we: Throwable) {
                System.err.println("virgl-session report write failed: " + we.message)
                if (failure == null) failure = we else failure!!.addSuppressed(we)
            }
        }
        if (failure != null) throw failure!!
    }
}
