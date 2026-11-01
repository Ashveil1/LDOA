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
 *  - READINESS: the session's own alive marker, bound to THIS launch's
 *    id line inside a fail-closed post-launch char window (log shrink or
 *    rotation fails the test, never silently re-reads stale content).
 *    Readiness alone proves NOTHING about the renderer.
 *  - COMPOSITOR-CONFIGURED-ROUTE: this session generation's gnome-shell
 *    process carries GALLIUM_DRIVER=virpipe in its OWN environ. This
 *    proves the compositor was LAUNCHED on the virgl route. It does NOT
 *    prove the compositor's GL context actually initialized through
 *    virgl, and says nothing about displayed pixels.
 *  - COMPOSITOR-RENDERER: stays UNKNOWN unless evidence attributable to
 *    gnome-shell's actual GL context exists (a shell-attributable
 *    renderer log line, or fd-to-inode correlation of ITS socket fd to
 *    the vtest socket). Socket-name rows in /proc/PID/net/unix are a
 *    network-NAMESPACE table, NOT fd ownership; a name row alone is
 *    never ATTRIBUTED. net/unix is EACCES on this emulator, so the
 *    expected result here is UNKNOWN.
 *  - PRESENTATION: mechanical capture of the actual desktop image (xwd
 *    -root of the X framebuffer when xwd exists in the rootfs - source
 *    tmp file deleted and re-created fresh, host copy mtime-bound to
 *    this invocation; else the Android-display screencap via the shot
 *    pipeline). The test asserts only that the capture mechanically
 *    succeeded; the REVIEWER verifies the artifact image actually shows
 *    the intended X desktop. Reviewer-verified presentation is PENDING
 *    until then. No stored screenshot expectation is invented.
 *  - STOP-CLEANUP: structured worker capture on the SESSION's render
 *    server, owned stop, identity-matched stop-return sampling,
 *    tri-state post checks.
 *  - SMOOTHNESS: NOT MEASURED - excluded from this qualification.
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

    /** Post-base log window, FAIL CLOSED on shrink/rotation (re-basing a
     *  shrunk file to 0 would re-admit the stale lines base excludes). */
    private fun window(base: Long): String? {
        if (!logFile.isFile) return ""
        val content = logFile.readText()
        if (content.length < base) return null
        return content.substring(base.toInt())
    }

    /** A marker line belonging to THIS launch generation: it must sit
     *  AFTER this launch's exact id line inside the window. */
    private fun lineOfThisLaunch(w: String, launchId: String, needle: String): String? {
        val idIdx = w.lastIndexOf("gnome-launch: launch id=" + launchId)
        if (idIdx < 0) return null
        val needleIdx = w.lastIndexOf(needle)
        if (needleIdx < idIdx) return null
        return w.substring(needleIdx).lineSequence().first().trim()
    }

    private data class ShellScan(
        val shells: List<VirglTestCommon.ProcObs>,
        val staleGeneration: Int,
        val unreadable: Int,
        val foreignUid: Int)

    /** gnome-shell processes belonging to THIS session generation:
     *  argv0 basename match, our uid, and a starttime no older than the
     *  recorded launch moment (uptime comparison; USER_HZ=100 assumed -
     *  raw values are recorded in the report so a wrong assumption is
     *  visible, not hidden). Every reject class is COUNTED and reported;
     *  nothing is swallowed as silent churn. uid comes from a separate
     *  metadata read (never claimed as same-snapshot). */
    private fun findGnomeShell(minStartSec: Double): ShellScan {
        val shells = ArrayList<VirglTestCommon.ProcObs>()
        var stale = 0; var unreadable = 0; var foreign = 0
        File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) }?.forEach { f ->
            val argv0 = try {
                File(f, "cmdline").readBytes().toString(Charsets.UTF_8).split('\u0000').firstOrNull()
            } catch (t: Throwable) { null } ?: return@forEach
            if (argv0.substringAfterLast('/') != "gnome-shell") return@forEach
            val st = try { File(f, "stat").readText() } catch (t: Throwable) { unreadable++; return@forEach }
            val close = st.lastIndexOf(')')
            if (close < 0) { unreadable++; return@forEach }
            val rest = st.substring(close + 1).trim().split(Regex("\\s+"))
            if (rest.size < 20) { unreadable++; return@forEach }
            val startTicks = rest[19].toLongOrNull()
            if (startTicks == null) { unreadable++; return@forEach }
            val uid = try { android.system.Os.stat(f.absolutePath).st_uid }
                      catch (t: Throwable) { unreadable++; return@forEach }
            if (uid != android.os.Process.myUid()) { foreign++; return@forEach }
            if (startTicks / 100.0 < minStartSec - 1.0) { stale++; return@forEach }
            shells.add(VirglTestCommon.ProcObs(f.name.toInt(), rest[0], rest[19], uid))
        }
        return ShellScan(shells, stale, unreadable, foreign)
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
        var qualified = false
        try {
            val installer = DistroInstaller(ctx)
            assertTrue("ubuntu not installed - full-lane autoenter setup must precede this test",
                installer.isInstalled(d))
            val rootfs = installer.rootfsDir(d)
            assertTrue("gnome not provisioned - full-lane autoenter setup must precede this test",
                GuestUbuntu.hasGnome(rootfs))

            // Warmup + reset happen BEFORE the opt-in flag is set, so the
            // warmup's own tracked launch (default renderer) can never
            // contaminate this test's virgl evidence world.
            SessionReset.trackedWarmup(ctx, d, rootfs) { m -> Log.i(TAG, "warmup: " + m) }
            out.append("pre-launch reset: ")
                .append(SessionReset.resetForFreshLaunch(ctx, d) { m -> Log.i(TAG, m) }).append('\n')

            // Opt IN for THIS launch only; the flag is restored in finally.
            flag.writeText("1")

            XServer.start(ctx)
            // CHAR base from one content snapshot BEFORE launch (never
            // File.length() bytes) + the launch-moment uptime for the
            // generation check.
            val base = (if (logFile.isFile) logFile.readText() else "").length.toLong()
            val launchUptimeSec = File("/proc/uptime").readText().split(' ')[0].toDouble()
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

            // READINESS: bounded 10-minute window; every launcher marker
            // bound to THIS launch's id line; rotation fails closed.
            val deadline = System.currentTimeMillis() + 600_000
            var ready = false
            var died: String? = null
            while (System.currentTimeMillis() < deadline) {
                val w = window(base)
                    ?: throw AssertionError("guest-gnome.log rotated/shrank under launch " + launchId +
                        " - marker binding is void (fail-closed)")
                if (lineOfThisLaunch(w, launchId!!, "gnome-launch: gnome-session alive") != null) {
                    ready = true; break
                }
                died = lineOfThisLaunch(w, launchId!!, "gnome-launch: session launcher died")
                    ?: lineOfThisLaunch(w, launchId!!, "falling back to bare gnome-shell")
                if (died != null) break
                // gnome-session's OWN failure output is not launcher-id
                // stamped; bound it to the post-launch window only (this
                // launch is the only session in the world at this point).
                if (w.contains("Oh no! Something has gone wrong") ||
                    w.contains("Cannot open display")) { died = "guest failure output in window"; break }
                Thread.sleep(5_000)
            }
            out.append("VERDICT readiness: ")
                .append(if (ready) "PASS (this launch's alive marker)"
                        else "FAIL (" + (died ?: "10min window expired") + ")").append('\n')
            assertTrue("READINESS failed - session never reached its own alive marker " +
                "(window tail: " + (window(base)?.takeLast(400) ?: "<rotated>") + ")", ready)

            // COMPOSITOR evidence, this generation only.
            val scan = findGnomeShell(launchUptimeSec)
            out.append("gnome-shell scan (this generation, launchUptimeSec=")
                .append(launchUptimeSec.toLong()).append("): found=")
                .append(scan.shells.joinToString(",") { it.pid.toString() + "/" + it.state })
                .append(" stale-generation=").append(scan.staleGeneration)
                .append(" unreadable=").append(scan.unreadable)
                .append(" foreign-uid=").append(scan.foreignUid).append('\n')
            if (scan.unreadable > 0) {
                out.append("WARNING: ").append(scan.unreadable)
                    .append(" gnome-shell-named process(es) UNREADABLE - compositor evidence is PARTIAL\n")
            }
            assertTrue("no gnome-shell process of THIS session generation found after readiness " +
                "(stale=" + scan.staleGeneration + " unreadable=" + scan.unreadable + ")",
                scan.shells.isNotEmpty())
            var envProven = 0
            for (p in scan.shells) {
                val env = try {
                    File("/proc/" + p.pid + "/environ").readBytes()
                        .toString(Charsets.UTF_8).split('\u0000')
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
                // fd-to-inode correlation: collect THIS process's socket
                // inodes, then require a net/unix row whose inode is one
                // of them AND whose path is the vtest socket. A bare
                // name row is namespace table content, never ATTRIBUTED.
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
            val rendLines = w2.lineSequence()
                .filter { it.contains("renderer", ignoreCase = true) &&
                    (it.contains("shell", ignoreCase = true) || it.contains("mutter", ignoreCase = true) ||
                     it.contains("clutter", ignoreCase = true)) }
                .take(5).toList()
            out.append("session-log shell-attributable renderer lines (post-launch window): ")
                .append(if (rendLines.isEmpty()) "NONE" else rendLines.joinToString(" | ").take(400)).append('\n')
            out.append("VERDICT compositor-configured-route: ")
                .append(if (envProven > 0) "PROVEN on " + envProven + "/" + scan.shells.size +
                    " this-generation gnome-shell process(es) (LAUNCHED on the virgl route;" +
                    " says nothing about the actual context or displayed pixels)"
                        else "NOT-PROVEN (no this-generation gnome-shell environ carried GALLIUM_DRIVER=virpipe)")
                .append('\n')
            out.append("VERDICT compositor-renderer: ")
                .append(if (rendLines.isNotEmpty()) "LOG-ATTRIBUTED (lines above; log attribution only)"
                        else "UNKNOWN (no evidence attributable to the compositor's actual GL context)")
                .append('\n')
            assertTrue("this-generation gnome-shell environ lacks GALLIUM_DRIVER=virpipe - " +
                "compositor was not launched on the virgl route", envProven > 0)

            // PRESENTATION: mechanical capture only; the reviewer verifies.
            val haveXwd = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c", "command -v xwd"),
                timeoutMs = 30_000).exitCode == 0
            if (haveXwd) {
                // Bind the capture to THIS invocation: the guest tmp file
                // is deleted first and must be re-created fresh.
                Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c", "rm -f /tmp/virgl-session-root.xwd"),
                    timeoutMs = 30_000)
                val invokeAt = System.currentTimeMillis()
                val r = Proot.runGuest(ctx, rootfs,
                    listOf("xwd", "-root", "-silent", "-out", "/tmp/virgl-session-root.xwd"),
                    extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"),
                    extraEnv = mapOf("DISPLAY" to ":0"), timeoutMs = 60_000)
                assertEquals("xwd -root capture failed: " + r.stderr.take(200), 0, r.exitCode)
                val src = File(rootfs, "tmp/virgl-session-root.xwd")
                assertTrue("xwd output missing in rootfs tmp", src.isFile && src.length() > 1000)
                assertTrue("xwd output predates this invocation (stale capture bound as fresh): mtime=" +
                    src.lastModified() + " invokedAt=" + invokeAt, src.lastModified() >= invokeAt - 5_000)
                src.copyTo(xwd, overwrite = true)
                out.append("VERDICT presentation: CAPTURE-MECHANICAL-SUCCESS via xwd -root (X server")
                    .append(" framebuffer), ").append(xwd.length())
                    .append(" bytes, artifact virgl-session-root.xwd. REVIEWER-VERIFIED presentation:")
                    .append(" PENDING - inspect the artifact image for the intended X desktop;")
                    .append(" black/uniform is sanity only, correctness is never a test assert\n")
            } else {
                // xwd absent from the stock GNOME rootfs and installing it
                // would mutate the provisioned rootfs (out of scope): the
                // Android-display screencap via the shot pipeline. The
                // WORKFLOW gates that the screenshot actually lands in the
                // artifact when this fallback was used - CAPTURED is never
                // marked on the Shot.save call alone.
                Shot.save("virgl-session-desktop")
                out.append("VERDICT presentation: capture attempted via Android-display screencap" +
                    " (shot pipeline, name virgl-session-desktop) - xwd ABSENT in rootfs, rootfs" +
                    " preserved unmodified. Artifact arrival is GATED CI-side; REVIEWER-VERIFIED" +
                    " presentation: PENDING (must show the intended X desktop, not another activity)\n")
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
            qualified = true
        } finally {
            // Independent attempts: session teardown, report persistence,
            // flag restore - none may be lost to another's failure.
            try {
                XServer.stopVirglServerForTest("virgl-session-cleanup")
                SessionReset.resetForFreshLaunch(ctx, d) { m -> Log.i(TAG, "teardown: " + m) }
            } catch (ce: Throwable) {
                out.append("== CLEANUP-ERROR teardown: ").append(ce.message).append('\n')
            } finally {
                try {
                    out.append(if (executed) "== EXECUTION: complete\n"
                               else "== EXECUTION: ABORTED - partial results above ==\n")
                    out.append(if (qualified) "== QUALIFICATION: passed\n"
                               else "== QUALIFICATION: FAILED or incomplete ==\n")
                    report.writeText(out.toString())
                } catch (we: Throwable) {
                    System.err.println("virgl-session report write failed: " + we.message)
                } finally {
                    if (savedFlag != null) flag.writeText(savedFlag) else flag.delete()
                }
            }
        }
    }
}
