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
 * Verdicts are SEPARATE and each is explicit:
 *  - READINESS: the session's own launch markers in guest-gnome.log
 *    (same contract as UbuntuGnomeSpikeTest). Readiness alone proves
 *    NOTHING about the renderer.
 *  - COMPOSITOR-RENDERER: evidence attributable to the gnome-shell
 *    process ITSELF - its own /proc environ must carry
 *    GALLIUM_DRIVER=virpipe (proves the compositor was LAUNCHED on the
 *    virgl route). vtest socket fd attribution needs /proc/PID/net/unix,
 *    EACCES on this emulator, so it is recorded UNKNOWN, never claimed.
 *    A renderer line in the session log attributable to gnome-shell is
 *    recorded when present, UNKNOWN when absent.
 *  - PRESENTATION: the ACTUAL X desktop image - xwd -root (reads the X
 *    server framebuffer) when xwd exists in the rootfs, else the
 *    Android-display screencap via the shot pipeline. The test asserts
 *    only that a capture succeeded; black/uniform is SANITY ONLY and
 *    desktop correctness is the reviewer's call on the artifact image,
 *    never a test assert. No stored screenshot expectation is invented.
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

    /** Host-side /proc scan for gnome-shell processes (proot guests are
     *  host processes under our uid). Identity (state+starttime+uid) is
     *  read from the SAME stat snapshot per process. */
    private fun findGnomeShell(): List<VirglTestCommon.ProcObs> {
        val out = ArrayList<VirglTestCommon.ProcObs>()
        File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) }?.forEach { f ->
            try {
                val argv0 = File(f, "cmdline").readBytes()
                    .toString(Charsets.UTF_8).split('\u0000').firstOrNull() ?: return@forEach
                if (argv0.substringAfterLast('/') != "gnome-shell") return@forEach
                val st = File(f, "stat").readText()
                val close = st.lastIndexOf(')')
                if (close < 0) return@forEach
                val rest = st.substring(close + 1).trim().split(Regex("\\s+"))
                if (rest.size < 20) return@forEach
                val uid = android.system.Os.stat(f.absolutePath).st_uid
                if (uid != android.os.Process.myUid()) return@forEach
                out.add(VirglTestCommon.ProcObs(f.name.toInt(), rest[0], rest[19], uid))
            } catch (t: Throwable) { /* vanished mid-scan: normal churn */ }
        }
        return out
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

            // Opt IN for THIS launch only; the flag is restored in finally.
            flag.writeText("1")

            // Fresh-launch world, identity-owned (same contract as the spike).
            SessionReset.trackedWarmup(ctx, d, rootfs) { m -> Log.i(TAG, "warmup: " + m) }
            out.append("pre-launch reset: ")
                .append(SessionReset.resetForFreshLaunch(ctx, d) { m -> Log.i(TAG, m) }).append('\n')

            XServer.start(ctx)
            XServer.launchOrReuseGnomeSession(ctx, d, rootfs)

            // The launch MUST have started its own render server: a missing
            // identity means a software fallback and every virgl verdict is
            // void - fail loudly, never infer from readiness.
            val srvIdent = XServer.virglServerIdentForTest()
            assertNotNull("opted-in session launch started NO virgl render server " +
                "(software fallback - virgl session verdict void)", srvIdent)
            val sock = XServer.virglCurrentSocketForTest()
            out.append("render-server: pid=").append(srvIdent!!.pid).append(" start=").append(srvIdent.start)
                .append(" uid=").append(srvIdent.uid).append(" sock=")
                .append(sock?.absolutePath ?: "?").append('\n')

            // READINESS: bounded 10-minute marker window on guest-gnome.log.
            val logFile = File(ctx.filesDir, "guest-gnome.log")
            val deadline = System.currentTimeMillis() + 600_000
            var ready = false
            var died = false
            while (System.currentTimeMillis() < deadline) {
                val text = if (logFile.isFile) logFile.readText() else ""
                if (text.contains("gnome-launch: gnome-session alive")) { ready = true; break }
                if (text.contains("gnome-launch: session launcher died") ||
                    text.contains("falling back to bare gnome-shell")) { died = true; break }
                if (text.contains("Oh no! Something has gone wrong") ||
                    text.contains("Cannot open display")) { died = true; break }
                Thread.sleep(5_000)
            }
            out.append("VERDICT readiness: ")
                .append(if (ready) "PASS (gnome-launch: gnome-session alive)"
                        else "FAIL (" + (if (died) "decisive failure marker" else "10min window expired") + ")")
                .append('\n')
            assertTrue("READINESS failed - session never reached its own alive marker (see guest-gnome.log)", ready)

            // COMPOSITOR-RENDERER: gnome-shell's OWN environ must carry the
            // virgl route; fd/log evidence recorded with honest UNKNOWNs.
            val shells = findGnomeShell()
            out.append("gnome-shell processes: ")
                .append(if (shells.isEmpty()) "NONE-FOUND"
                        else shells.joinToString(",") { it.pid.toString() + "/" + it.state }).append('\n')
            assertTrue("no gnome-shell process found after readiness - compositor evidence impossible",
                shells.isNotEmpty())
            var envProven = 0
            for (p in shells) {
                val env = try {
                    File("/proc/" + p.pid + "/environ").readBytes()
                        .toString(Charsets.UTF_8).split('\u0000')
                } catch (t: Throwable) {
                    out.append("shell ").append(p.pid).append(" environ read FAILED: ")
                        .append(t.message).append('\n')
                    null
                }
                val hasVirgl = env?.contains("GALLIUM_DRIVER=virpipe") == true
                if (hasVirgl) envProven++
                out.append("shell ").append(p.pid).append(" environ GALLIUM_DRIVER=virpipe: ")
                    .append(if (env == null) "UNKNOWN(read-failed)" else hasVirgl.toString()).append('\n')
                // vtest socket fd attribution: socket fds are anonymous
                // inodes; resolving them to the vtest socket needs
                // /proc/PID/net/unix - EACCES on this emulator.
                val unix = try { File("/proc/" + p.pid + "/net/unix").readText() } catch (t: Throwable) { null }
                out.append("shell ").append(p.pid).append(" vtest-socket-fd attribution: ")
                    .append(if (unix == null) "UNKNOWN (net/unix unreadable - EACCES on this emulator)"
                            else if (sock != null && unix.contains(sock.name)) "ATTRIBUTED (socket name row present)"
                            else "NOT-FOUND in net/unix").append('\n')
            }
            val logText = if (logFile.isFile) logFile.readText() else ""
            val rendLines = logText.lineSequence()
                .filter { it.contains("renderer", ignoreCase = true) &&
                    (it.contains("shell", ignoreCase = true) || it.contains("mutter", ignoreCase = true) ||
                     it.contains("clutter", ignoreCase = true) || it.contains("virgl", ignoreCase = true)) }
                .take(5).toList()
            out.append("session-log compositor renderer lines: ")
                .append(if (rendLines.isEmpty()) "ABSENT (UNKNOWN)"
                        else rendLines.joinToString(" | ").take(400)).append('\n')
            out.append("VERDICT compositor-renderer: ")
                .append(if (envProven > 0) "ENV-PROVEN on " + envProven + "/" + shells.size +
                    " gnome-shell process(es) (launched on the virgl route; does NOT prove the displayed" +
                    " pixels came through virgl - see presentation)"
                        else "NOT-PROVEN (no gnome-shell environ carried GALLIUM_DRIVER=virpipe)").append('\n')
            assertTrue("gnome-shell's OWN environ lacks GALLIUM_DRIVER=virpipe - compositor not on the virgl route",
                envProven > 0)

            // PRESENTATION: the actual desktop image. Asserted: capture
            // success ONLY. Correctness is reviewed on the artifact image.
            val haveXwd = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c", "command -v xwd"),
                timeoutMs = 30_000).exitCode == 0
            if (haveXwd) {
                val r = Proot.runGuest(ctx, rootfs,
                    listOf("xwd", "-root", "-silent", "-out", "/tmp/virgl-session-root.xwd"),
                    extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"),
                    extraEnv = mapOf("DISPLAY" to ":0"), timeoutMs = 60_000)
                assertEquals("xwd -root capture failed: " + r.stderr.take(200), 0, r.exitCode)
                val src = File(rootfs, "tmp/virgl-session-root.xwd")
                assertTrue("xwd output missing in rootfs tmp", src.isFile && src.length() > 1000)
                src.copyTo(xwd, overwrite = true)
                out.append("VERDICT presentation: CAPTURED via xwd -root (X server framebuffer), ")
                    .append(xwd.length()).append(" bytes, artifact virgl-session-root.xwd - the image is")
                    .append(" the evidence; black/uniform is harvest-side SANITY ONLY; desktop")
                    .append(" correctness is reviewer inspection, never a test assert\n")
            } else {
                // xwd absent from the stock GNOME rootfs and installing it
                // would mutate the provisioned rootfs (out of scope): fall
                // back to the Android-display screencap via the shot
                // pipeline (logcat PHONESHOT, reassembled by CI).
                Shot.save("virgl-session-desktop")
                out.append("VERDICT presentation: CAPTURED via Android-display screencap (shot pipeline," +
                    " name virgl-session-desktop) - xwd ABSENT in rootfs, rootfs preserved unmodified;" +
                    " same sanity/correctness limits as above\n")
            }

            // STOP-CLEANUP on the SESSION's render server.
            val scan = VirglTestCommon.scanChildren(srvIdent.pid)
            val workers = (scan.viaChildrenFile + scan.viaPpidScan)
                .filter { it.state != "Z" }.distinctBy { it.pid }
            out.append("session-server workers at stop: ")
                .append(if (workers.isEmpty()) "NONE-OBSERVED"
                        else workers.joinToString(",") { it.pid.toString() + "/" + it.state })
                .append(" children-file-error: ").append(scan.childrenFileError ?: "none")
                .append(" ppid-scan-error: ").append(scan.scanError ?: "none").append('\n')
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
