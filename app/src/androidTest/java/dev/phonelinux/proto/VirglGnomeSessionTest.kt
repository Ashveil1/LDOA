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
 *  - PRESENTATION: mechanical capture only (unique-per-invocation xwd
 *    path with absence-then-freshness binding; else the shot pipeline
 *    with the CI-side artifact-arrival gate). The test asserts only
 *    mechanical success; REVIEWER-VERIFIED presentation stays PENDING
 *    until the artifact image is inspected for the intended X desktop.
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
        val xwd = File(ctx.filesDir, "virgl-session-root.xwd")
        val out = StringBuilder()
        // Clear stale artifacts: an unreached stage must never leave a
        // previous run's image or summary looking current.
        report.delete(); xwd.delete()
        val savedFlag = if (flag.isFile) flag.readText() else null
        var executed = false
        var checksPassed = false
        var rendererVerdict = "UNKNOWN (stage not reached)"
        var presentationVerdict = "REVIEWER-VERIFICATION PENDING (stage not reached)"
        var failure: Throwable? = null
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

            // Opt IN for THIS launch only; the flag is restored in finally.
            flag.writeText("1")

            XServer.start(ctx)
            // CHAR base from one content snapshot BEFORE launch (never
            // File.length() bytes) + the launch-moment uptime for the
            // freshness candidate check (runtime clk_tck, no USER_HZ).
            val base = (if (logFile.isFile) logFile.readText() else "").length.toLong()
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
            assertNotNull("current launch identity unavailable - cannot bind evidence to THIS launch " +
                "(failing clearly, not scanning an unbound window)", launchId)

            // The launch MUST have started its own render server: a missing
            // identity means a software fallback and every virgl verdict is
            // void - fail loudly, never infer from readiness.
            val srvIdent = XServer.virglServerIdentForTest()
            assertNotNull("opted-in session launch started NO virgl render server " +
                "(software fallback - virgl session verdict void)", srvIdent)
            val sock = XServer.virglCurrentSocketForTest()
            out.append("render-server: pid=").append(srvIdent!!.pid).append(" start=").append(srvIdent.start)
                .append(" uid=").append(srvIdent.uid).append(" sock=")
                .append(sock?.absolutePath ?: "?").append(" launchId=").append(launchId).append('\n')

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
            assertTrue("READINESS failed - latest state of THIS generation never reached alive " +
                "(window tail: " + (window(base)?.takeLast(400) ?: "<rotated>") + ")", ready)
            val gsPid = aliveLine!!.substringAfter("gnome-session alive pid=")
                .takeWhile { it.isDigit() }.toInt()

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
            out.append("VERDICT compositor-renderer: ").append(rendererVerdict).append('\n')
            assertTrue("owned this-generation gnome-shell environ lacks GALLIUM_DRIVER=virpipe - " +
                "compositor was not launched on the virgl route", envProven > 0)

            // PRESENTATION: mechanical capture only; the reviewer verifies.
            val haveXwd = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c", "command -v xwd"),
                timeoutMs = 30_000).exitCode == 0
            if (haveXwd) {
                // Unique-per-invocation guest path (never a trusted rm of
                // a fixed name): a collision must be removed with the
                // removal CONFIRMED, else the test fails before capture.
                val guestTmp = "/tmp/virgl-session-root-" + launchId + ".xwd"
                val src = File(rootfs, "tmp/virgl-session-root-" + launchId + ".xwd")
                if (src.exists()) {
                    val rm = Proot.runGuest(ctx, rootfs,
                        listOf("/bin/sh", "-c", "rm -f " + guestTmp + " && test ! -e " + guestTmp),
                        timeoutMs = 30_000)
                    assertEquals("stale capture path " + guestTmp + " not confirmed removed " +
                        "(exit=" + rm.exitCode + ")", 0, rm.exitCode)
                }
                val invokeAt = System.currentTimeMillis()
                val r = Proot.runGuest(ctx, rootfs,
                    listOf("xwd", "-root", "-silent", "-out", guestTmp),
                    extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"),
                    extraEnv = mapOf("DISPLAY" to ":0"), timeoutMs = 60_000)
                assertEquals("xwd -root capture failed: " + r.stderr.take(200), 0, r.exitCode)
                // Fresh-bytes binding: the file at the unique path must
                // exist, be non-trivial, and carry an mtime at/after this
                // invocation (5s clock-skew slack).
                assertTrue("xwd output missing/trivial at unique path " + guestTmp,
                    src.isFile && src.length() > 1000)
                assertTrue("xwd output predates this invocation (stale capture bound as fresh): mtime=" +
                    src.lastModified() + " invokedAt=" + invokeAt, src.lastModified() >= invokeAt - 5_000)
                src.copyTo(xwd, overwrite = true)
                presentationVerdict = "CAPTURE-MECHANICAL-SUCCESS via xwd -root (unique path " +
                    guestTmp + ", " + xwd.length() + " bytes, artifact virgl-session-root.xwd);" +
                    " REVIEWER-VERIFIED presentation: PENDING - inspect the artifact image for the" +
                    " intended X desktop; black/uniform is sanity only"
                out.append("VERDICT presentation: ").append(presentationVerdict).append('\n')
            } else {
                // xwd absent from the stock GNOME rootfs and installing it
                // would mutate the provisioned rootfs (out of scope): the
                // Android-display screencap via the shot pipeline. The
                // WORKFLOW gates that the screenshot actually lands in the
                // artifact when this fallback was used - CAPTURED is never
                // marked on the Shot.save call alone.
                Shot.save("virgl-session-desktop")
                presentationVerdict = "capture attempted via Android-display screencap (shot pipeline," +
                    " name virgl-session-desktop) - xwd ABSENT in rootfs, rootfs preserved unmodified;" +
                    " artifact arrival GATED CI-side; REVIEWER-VERIFIED presentation: PENDING (must" +
                    " show the intended X desktop, not another activity)"
                out.append("VERDICT presentation: ").append(presentationVerdict).append('\n')
            }

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
            try {
                if (savedFlag != null) flag.writeText(savedFlag) else flag.delete()
            } catch (fe: Throwable) {
                if (failure == null) failure = fe else failure!!.addSuppressed(fe)
            }
        }
        if (failure != null) throw failure!!
    }
}
