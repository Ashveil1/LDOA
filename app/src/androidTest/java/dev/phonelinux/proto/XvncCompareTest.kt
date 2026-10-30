package dev.phonelinux.proto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Stage-1 isolated comparison: SAME GNOME session script on the vendored
 * lorie server (:0) vs a TigerVNC Xvnc fixture (:1), one identical scripted
 * workload per arm. TEST-ONLY and disposable: tigervnc-standalone-server +
 * mesa-utils are runtime fixture installs (never added to the product
 * manifest); no product code changes; revert this file to erase.
 *
 * Self-skip: runs only with -e xvncCompare 1, so the every-push
 * emulator-test lane treats it as a cheap skip.
 *
 * Identity of arms: both run GuestUbuntu.gnomeSessionScript() with exactly
 * two textual changes, applied identically to both: DISPLAY retargeted to
 * the arm's server, and CLUTTER_SHOW_FPS=1 exported (measurement-only, mutter
 * 46.2 support source-verified). Everything else - gnome-session attempt,
 * ICE/fallback logic, env, dock enable - is byte-identical, so a session-mode
 * difference between arms is a defect of the comparison, not data: the test
 * FAILS with SESSION MISMATCH instead of contrasting a full session against a
 * bare fallback (known x86 ICE ENOSYS gap).
 *
 * LIMITS (stated wherever numbers are logged): CI x86_64 GL is not the
 * POCO's kgsl path, so arm numbers are mechanism evidence ONLY and are not
 * predictive of device fps. CLUTTER_SHOW_FPS measures the compositor's own
 * frame stats; it is NOT end-to-end display FPS and says nothing about
 * input latency. The VNC viewer's decode/input cost is absent here by
 * design (no viewer attached; Xvnc serves nothing in this test).
 */
@RunWith(AndroidJUnit4::class)
class XvncCompareTest {
    private val TAG = "PHONELINUX_XVNC_CMP"

    private data class ArmResult(
        val name: String, val mode: String, val readyShellWins: Int,
        val stepWallMs: Map<String, Long>, val glxinfo: String, val fpsLines: String)

    @Test
    fun xvncVsLorieIdenticalSessionComparison(): Unit = runBlocking {
        assumeTrue("xvncCompare arg not set - Stage-1 comparison skipped",
            InstrumentationRegistry.getArguments().getString("xvncCompare") == "1")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val d = Distros.UBUNTU
        val installer = DistroInstaller(ctx)
        assertTrue("ubuntu rootfs must already be installed by the lane's earlier steps",
            installer.isInstalled(d))
        val rootfs = installer.rootfsDir(d)
        assertTrue("gnome must already be provisioned by the lane's earlier steps",
            GuestUbuntu.hasGnome(rootfs))

        // Runtime fixture installs (disposable, labeled; same pattern as the
        // existing xdotool/xterm fixtures - the product manifest is untouched).
        fun fixtureInstall(pkg: String, bin: String) {
            if (guest(ctx, rootfs, null, "command -v $bin || true").trim().isNotEmpty()) return
            val r = guest(ctx, rootfs, null,
                "export DEBIAN_FRONTEND=noninteractive; timeout 180 apt-get install -y $pkg 2>&1 | tail -3 || true")
            Log.i(TAG, "cmp: fixture install $pkg tail=" + r.replace("\n", " | "))
        }
        fixtureInstall("tigervnc-standalone-server", "Xvnc")
        fixtureInstall("mesa-utils", "glxinfo")
        assertTrue("Xvnc fixture unavailable - environment/fixture gap, no comparison evidence",
            guest(ctx, rootfs, null, "command -v Xvnc || true").trim().isNotEmpty())

        val results = mutableListOf<ArmResult>()

        // ---- ARM A: lorie :0 (product display server, started the product way)
        resetGuests(ctx, rootfs)
        XServer.start(ctx)
        results += runArm(ctx, rootfs, "lorie", ":0", bindX = true, geometry = null,
            script = cmpScript(":0"))

        // ---- ARM B: Xvnc :1, SAME resolution/depth as lorie reported
        resetGuests(ctx, rootfs)
        val geometry = lastLorieGeometry
        assertTrue("lorie geometry unknown - cannot match Xvnc resolution, comparison void",
            geometry != null)
        val xvncStart = guest(ctx, rootfs, null,
            "( Xvnc :1 -geometry $geometry -depth 24 -SecurityTypes None -ac >/root/xvnc.log 2>&1 & " +
                "echo ${'$'}! > /root/xvnc.pid ); sleep 2; XP=${'$'}(cat /root/xvnc.pid 2>/dev/null); " +
                "echo pid=${'$'}XP; if kill -0 ${'$'}XP 2>/dev/null; then echo alive-after-2s; else echo exited-early; fi")
        Log.i(TAG, "cmp: Xvnc start " + xvncStart.replace("\n", " | "))
        assertTrue("Xvnc fixture failed to stay up - environment/fixture gap: " + xvncStart.trim(),
            xvncStart.contains("alive-after-2s"))
        results += runArm(ctx, rootfs, "xvnc", ":1", bindX = false, geometry = geometry,
            script = cmpScript(":1"))

        // ---- Session-mode identity gate: never contrast mismatched shapes.
        assertTrue("SESSION MISMATCH - arms landed in different session shapes " +
            "(lorie=" + results[0].mode + " xvnc=" + results[1].mode + "); " +
            "comparison void, this is a comparison-harness defect not data",
            results[0].mode == results[1].mode)

        // ---- Evidence summary (logged, never asserted).
        for (r in results) {
            Log.i(TAG, "cmp: arm=" + r.name + " mode=" + r.mode + " readyShellWins=" + r.readyShellWins +
                " steps=" + r.stepWallMs.filterKeys { it != "geometry" })
            Log.i(TAG, "cmp: arm=" + r.name + " glxinfo-B (SERVER-side GLX view; lorie's GLX is a " +
                "swrast stub by its own source comment; NOT the compositor's EGL context): " +
                r.glxinfo.replace("\n", " | "))
            Log.i(TAG, "cmp: arm=" + r.name + " clutter-fps-lines=[" + r.fpsLines + "]")
        }
        Log.i(TAG, "cmp: LIMITS - CI x86 GL is not the POCO kgsl path (mechanism evidence only, " +
            "not device-predictive); compositor FPS is not end-to-end display FPS or input latency; " +
            "no viewer attached, so VNC decode/input cost is absent by design")
    }

    private var lastLorieGeometry: String? = null

    private fun cmpScript(display: String): String {
        val s = GuestUbuntu.gnomeSessionScript()
        val needle = "export DISPLAY=:0 "
        assertTrue("gnomeSessionScript shape changed; update the comparison transform", s.contains(needle))
        return s.replace(needle, "export DISPLAY=$display CLUTTER_SHOW_FPS=1 ")
    }

    private fun guest(ctx: android.content.Context, rootfs: File, display: String?, cmd: String,
                      bindX: Boolean = false): String {
        val binds = if (bindX) listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix") else emptyList()
        val r = Proot.runGuest(ctx, rootfs,
            listOf("/bin/sh", "-c", (if (display != null) "export DISPLAY=$display; " else "") + cmd),
            extraBinds = binds)
        return r.stdout
    }

    /** Identity-scoped reset for THIS test's CI-single-session context:
     *  kills only gnome-session/gnome-shell/Xvnc cmdline matches (the
     *  established lane pattern), then verifies they are gone. */
    private fun resetGuests(ctx: android.content.Context, rootfs: File) {
        val kill = guest(ctx, rootfs, null,
            "for f in /proc/[0-9]*/cmdline; do c=${'$'}(tr '\\0' ' ' < ${'$'}f 2>/dev/null); " +
                "case ${'$'}c in *gnome-session*|*gnome-shell*|*Xvnc*) echo ${'$'}f | cut -d/ -f3;; esac; " +
                "done | xargs -r kill 2>/dev/null; sleep 3; " +
                "for f in /proc/[0-9]*/cmdline; do c=${'$'}(tr '\\0' ' ' < ${'$'}f 2>/dev/null); " +
                "case ${'$'}c in *gnome-session*|*gnome-shell*|*Xvnc*) echo leftover=${'$'}f;; esac; done; echo reset-done")
        Log.i(TAG, "cmp: reset " + kill.replace("\n", " | "))
    }

    private fun runArm(ctx: android.content.Context, rootfs: File, name: String, display: String,
                       bindX: Boolean, geometry: String?, script: String): ArmResult {
        File(rootfs, "root/cmp-session-$name.sh").writeText(script)
        val nd = ctx.applicationInfo.nativeLibraryDir
        val log = File(ctx.filesDir, "cmp-$name.log")
        if (log.isFile) log.delete()
        val pb = ProcessBuilder(
            File(nd, "libproot.so").absolutePath, "--link2symlink", "-0",
            "-r", rootfs.absolutePath, "-b", "/dev", "-b", "/proc", "-b", "/sys",
            * (if (bindX) arrayOf("-b", XServer.socketDir(ctx).absolutePath + ":/tmp/.X11-unix") else emptyArray()),
            "-w", "/root", "/bin/sh", "/root/cmp-session-$name.sh")
        pb.environment().putAll(mapOf(
            "PROOT_LOADER" to File(nd, "libproot_loader.so").absolutePath,
            "PROOT_LOADER_32" to File(nd, "libproot_loader32.so").absolutePath,
            "LD_LIBRARY_PATH" to nd, "HOME" to "/root", "DISPLAY" to display,
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_TMP_DIR" to Proot.prootTmpDir(ctx).absolutePath, "TMPDIR" to "/tmp"))
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        val proc = pb.start()

        // Readiness (same shell-window proxy as the other lanes; readiness
        // PROXY, not a proved-usable desktop).
        var wins = 0
        val readyDeadline = System.currentTimeMillis() + 240_000
        while (System.currentTimeMillis() < readyDeadline) {
            wins = guest(ctx, rootfs, display,
                "xwininfo -root -tree 2>/dev/null | grep -ciE 'gnome.shell|gnome-shell' || true",
                bindX = bindX).trim().toIntOrNull() ?: 0
            if (wins >= 3 || !proc.isAlive) break
            Thread.sleep(10_000)
        }
        val logText = { if (log.isFile) log.readText() else "" }
        val mode = when {
            logText().contains("falling back to bare gnome-shell") -> "bare-shell-fallback"
            logText().contains("gnome-session alive") -> "gnome-session"
            else -> "unknown"
        }
        Log.i(TAG, "cmp: arm=$name readiness shell-windows=$wins mode=$mode alive=${proc.isAlive}")
        assertTrue("cmp: arm=$name session process died before readiness - environment/fixture gap, " +
            "not comparison data", proc.isAlive)
        assertTrue("cmp: arm=$name never reached the shell-window readiness proxy - " +
            "environment/fixture gap, not comparison data", wins >= 3)

        // Geometry of the lorie server, captured for the Xvnc arm to match.
        if (geometry == null) {
            val dim = guest(ctx, rootfs, display, "xdpyinfo | grep dimensions:", bindX = true)
                .replace(Regex(".*dimensions:\\s*"), "").replace(Regex("\\s+pixels.*"), "").trim()
            lastLorieGeometry = dim
            Log.i(TAG, "cmp: lorie geometry=$dim")
        }

        // Identical workload, fixed steps, wall-timed.
        val steps = linkedMapOf<String, Long>()
        fun step(label: String, body: () -> Unit) {
            val t0 = System.currentTimeMillis(); body(); steps[label] = System.currentTimeMillis() - t0
        }
        step("open-files") {
            guest(ctx, rootfs, display, "( nautilus >/root/cmp-nautilus-$name.log 2>&1 & )", bindX)
            val dl = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < dl) {
                val n = guest(ctx, rootfs, display,
                    "xwininfo -root -tree | grep -ci nautilus || true", bindX).trim().toIntOrNull() ?: 0
                if (n >= 1) break
                Thread.sleep(3_000)
            }
        }
        step("scroll-x5") { repeat(5) {
            guest(ctx, rootfs, display, "xdotool key Down", bindX); Thread.sleep(1_000) } }
        step("overview-toggle") {
            guest(ctx, rootfs, display, "xdotool key super", bindX); Thread.sleep(3_000)
            guest(ctx, rootfs, display, "xdotool key super", bindX); Thread.sleep(3_000)
        }

        val glx = guest(ctx, rootfs, display, "glxinfo -B 2>&1 | head -25", bindX)
        val fps = logText().lines().filter { it.contains("fps", ignoreCase = true) ||
            it.contains("Frame", ignoreCase = false) }.takeLast(10).joinToString(" | ")

        // Teardown: our tracked session process first, then the identity-
        // scoped sweep (reset), then verify; fixture files we created in the
        // guest are removed; evidence logs in filesDir are kept.
        proc.destroy()
        Thread.sleep(2_000)
        resetGuests(ctx, rootfs)
        guest(ctx, rootfs, null, "rm -f /root/cmp-session-$name.sh /root/cmp-nautilus-$name.log")
        Log.i(TAG, "cmp: arm=$name teardown done, proot alive=${proc.isAlive}")

        return ArmResult(name, mode, wins, steps, glx, fps)
    }
}
