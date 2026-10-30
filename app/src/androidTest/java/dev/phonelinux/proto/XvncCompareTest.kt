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
 * PROCESS OWNERSHIP (no name-only signaling): every process this test
 * signals is either (a) a Process object it started itself, or (b) found by
 * walking /proc children from a captured launch root identity, with pid +
 * start-time + uid re-read and matched immediately before the signal
 * (pid-reuse guard). FOREIGN pre-existing processes (present in the
 * test-start snapshot) are NEVER signaled: identity revalidation proves
 * identity, not ownership. Instead the test REQUIRES a clean start - if any
 * session-like process already exists (lane-sequencing leftover), it is a
 * harness/environment precondition failure: the test aborts comparison-
 * invalid and leaves every foreign process untouched. Post-teardown, any
 * remaining matching process is logged with its full identity and left
 * alone.
 *
 * glxinfo -B is logged per arm as the X SERVER's own GLX view only
 * (lorie's GLX is a swrast stub by its own source comment; Xvnc's is
 * likewise server-internal): it is NOT the compositor's EGL context and no
 * mutter/gnome-shell renderer inference is drawn from it (probe-client !=
 * actual context).
 *
 * LIMITS (stated wherever numbers are logged): CI x86_64 GL is not the
 * POCO's GPU path, so arm numbers are mechanism evidence ONLY and are not
 * predictive of device fps. CLUTTER_SHOW_FPS measures the compositor's own
 * frame stats; it is NOT end-to-end display FPS and says nothing about
 * input latency. No viewer is attached: VNC decode/input cost is absent by
 * design.
 */
@RunWith(AndroidJUnit4::class)
class XvncCompareTest {
    private val TAG = "PHONELINUX_XVNC_CMP"

    private class PIdent(val pid: Int, val start: String, val uid: Int, val cmd: String) {
        override fun toString() = "pid=$pid start=$start uid=$uid cmd=${cmd.take(60)}"
    }

    private fun identityOf(pid: Int): PIdent? = try {
        val stat = File("/proc/$pid/stat").readText()
        val start = stat.substringAfterLast(") ").split(" ")[19] // field 22 overall
        val uid = File("/proc/$pid/status").readText().lineSequence()
            .first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1].toInt()
        val cmd = File("/proc/$pid/cmdline").readText().replace(Regex("\\x00"), " ").trim()
        PIdent(pid, start, uid, cmd)
    } catch (t: Throwable) { null }

    private fun childrenOf(pid: Int): List<Int> = try {
        File("/proc/$pid/task").listFiles()?.flatMap { t ->
            File(t, "children").readText().trim().split(Regex("\\s+"))
                .filter { it.isNotEmpty() }.map { it.toInt() }
        } ?: emptyList()
    } catch (t: Throwable) { emptyList() }

    private fun treeOf(root: Int): Set<Int> {
        val out = linkedSetOf<Int>(); val stack = mutableListOf(root)
        while (stack.isNotEmpty()) {
            val p = stack.removeAt(stack.size - 1)
            if (out.add(p)) stack.addAll(childrenOf(p))
        }
        return out
    }

    private fun pidOfProcess(p: Process): Int? = try {
        val f = p.javaClass.getDeclaredField("pid"); f.isAccessible = true; f.getInt(p)
    } catch (t: Throwable) { null }

    /** Verified kill: signal only if the live identity still matches the
     *  identity captured at discovery (pid + start + uid). Returns pids
     *  that did NOT match (pid reuse) or would not die. */
    private fun killVerified(idents: Collection<PIdent>, why: String): List<Int> {
        val left = mutableListOf<Int>()
        for (want in idents) {
            val cur = identityOf(want.pid)
            if (cur == null) continue // already gone
            if (cur.start != want.start || cur.uid != want.uid) {
                Log.w(TAG, "cmp: SKIP signal - pid reused: want[$want] now[$cur] ($why)")
                left += want.pid; continue
            }
            Log.i(TAG, "cmp: kill $why [$cur]")
            try { android.os.Process.killProcess(want.pid) } catch (t: Throwable) { left += want.pid }
        }
        return left
    }

    private fun guest(ctx: android.content.Context, rootfs: File, display: String?, cmd: String,
                      bindX: Boolean = false, fakeRoot: Boolean = false): String {
        val binds = if (bindX) listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix") else emptyList()
        return Proot.runGuest(ctx, rootfs,
            listOf("/bin/sh", "-c", (if (display != null) "export DISPLAY=$display; " else "") + cmd),
            extraBinds = binds, fakeRoot = fakeRoot).stdout
    }

    /** All current pids whose cmdline matches the comparison's session
     *  patterns, with full identity (for foreign-snapshot + verification). */
    private fun sessionLikeProcs(): List<PIdent> =
        (File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) } ?: emptyArray())
            .mapNotNull { identityOf(it.name.toInt()) }
            .filter { it.cmd.contains("gnome-session") || it.cmd.contains("gnome-shell") ||
                it.cmd.contains("Xvnc") || it.cmd.contains("cmp-session") || it.cmd.contains("nautilus") }

    private var lastLorieGeometry: String? = null
    private val trackedProcs = mutableListOf<Pair<String, Process>>()

    private fun launchGuestProcess(ctx: android.content.Context, rootfs: File, name: String,
                                   display: String, bindX: Boolean, log: File,
                                   env: Map<String, String>, guestCmd: List<String>): Process {
        val nd = ctx.applicationInfo.nativeLibraryDir
        val args = mutableListOf(File(nd, "libproot.so").absolutePath, "--link2symlink", "-0",
            "-r", rootfs.absolutePath, "-b", "/dev", "-b", "/proc", "-b", "/sys")
        if (bindX) { args += "-b"; args += XServer.socketDir(ctx).absolutePath + ":/tmp/.X11-unix" }
        args += "-w"; args += "/root"; args.addAll(guestCmd)
        val pb = ProcessBuilder(args)
        pb.environment().putAll(mapOf(
            "PROOT_LOADER" to File(nd, "libproot_loader.so").absolutePath,
            "PROOT_LOADER_32" to File(nd, "libproot_loader32.so").absolutePath,
            "LD_LIBRARY_PATH" to nd, "HOME" to "/root", "DISPLAY" to display,
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_TMP_DIR" to Proot.prootTmpDir(ctx).absolutePath, "TMPDIR" to "/tmp"))
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        return pb.start().also { trackedProcs += name to it }
    }

    private fun cmpScript(display: String): String {
        val s = GuestUbuntu.gnomeSessionScript()
        val needle = "export DISPLAY=:0 "
        assertTrue("gnomeSessionScript shape changed; update the comparison transform", s.contains(needle))
        return s.replace(needle, "export DISPLAY=$display CLUTTER_SHOW_FPS=1 ")
    }

    private data class ArmResult(val name: String, val mode: String, val readyShellWins: Int,
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

        // Foreign snapshot: everything session-like that exists BEFORE this
        // test launches anything (lane leftovers from earlier instr steps).
        val foreign = sessionLikeProcs()
        Log.i(TAG, "cmp: foreign snapshot: " + foreign.joinToString(" || "))

        fun fixtureInstall(pkg: String, bin: String) {
            if (guest(ctx, rootfs, null, "command -v $bin || true").trim().isNotEmpty()) return
            val r = guest(ctx, rootfs, null,
                "export DEBIAN_FRONTEND=noninteractive; timeout 180 apt-get install -y $pkg 2>&1 | tail -3 || true",
                fakeRoot = true) // apt/dpkg refuse to install as non-root euid (Proot.kt)
            Log.i(TAG, "cmp: fixture install $pkg tail=" + r.replace("\n", " | "))
        }
        fixtureInstall("tigervnc-standalone-server", "Xvnc")
        fixtureInstall("mesa-utils", "glxinfo")
        assertTrue("Xvnc fixture unavailable - environment/fixture gap, no comparison evidence",
            guest(ctx, rootfs, null, "command -v Xvnc || true").trim().isNotEmpty())

        // Clean-start precondition (main 5:13:11): foreign pre-test processes
        // are NEVER signaled by this test - identity revalidation proves
        // identity, not ownership. If a lane-sequencing leftover session is
        // present, the comparison cannot establish a clean start: abort
        // comparison-invalid (environment/fixture gap) and leave them alone.
        assertTrue("cmp: clean start not established - pre-existing session-like " +
            "processes present, environment/fixture gap (left untouched, " +
            "comparison-invalid, not product evidence): " +
            foreign.joinToString(" || "), foreign.isEmpty())

        val results = mutableListOf<ArmResult>()

        // ARM A: lorie :0 via the product server start (its own lifecycle).
        XServer.start(ctx)
        results += runArm(ctx, rootfs, "lorie", ":0", bindX = true, geometry = null)

        // ARM B: Xvnc :1 as a TRACKED FOREGROUND process (no guest-background
        // orphan), same resolution/depth as lorie reported.
        val geometry = lastLorieGeometry
        assertTrue("lorie geometry unknown - cannot match Xvnc resolution, comparison void",
            geometry != null)
        val xvncLog = File(ctx.filesDir, "cmp-xvnc-server.log")
        if (xvncLog.isFile) xvncLog.delete()
        val xvncProc = launchGuestProcess(ctx, rootfs, "xvnc-server", ":1", bindX = false,
            log = xvncLog, env = emptyMap(),
            guestCmd = listOf("/usr/bin/Xvnc", ":1", "-geometry", geometry!!, "-depth", "24",
                "-SecurityTypes", "None", "-ac"))
        Thread.sleep(3_000)
        assertTrue("Xvnc fixture exited at start - environment/fixture gap, log tail: " +
            (if (xvncLog.isFile) xvncLog.readText().takeLast(300) else "<none>"), xvncProc.isAlive)
        try {
            results += runArm(ctx, rootfs, "xvnc", ":1", bindX = false, geometry = geometry)
        } finally {
            xvncProc.destroy()
        }

        assertTrue("SESSION MISMATCH - arms landed in different session shapes " +
            "(lorie=" + results[0].mode + " xvnc=" + results[1].mode + "); comparison void, " +
            "this is a comparison-harness defect not data",
            results[0].mode == results[1].mode)

        for (r in results) {
            Log.i(TAG, "cmp: arm=" + r.name + " mode=" + r.mode + " readyShellWins=" + r.readyShellWins +
                " steps=" + r.stepWallMs)
            Log.i(TAG, "cmp: arm=" + r.name + " glxinfo-B (SERVER-side GLX view only; NOT the " +
                "compositor's EGL context, no mutter inference): " + r.glxinfo.replace("\n", " | "))
            Log.i(TAG, "cmp: arm=" + r.name + " clutter-fps-lines=[" + r.fpsLines + "]")
        }
        Log.i(TAG, "cmp: LIMITS - CI x86 GL is not the POCO GPU path (mechanism evidence only, " +
            "not device-predictive); compositor FPS is not end-to-end display FPS or input " +
            "latency; no viewer attached, so VNC decode/input cost is absent by design")

        // Final verification: nothing session-like remains except documented
        // foreign pids (left running, never signaled by this test).
        val remaining = sessionLikeProcs()
        val foreignIds = foreign.map { it.pid to it.start }.toSet()
        val unowned = remaining.filter { (it.pid to it.start) !in foreignIds }
        if (remaining.isNotEmpty())
            Log.i(TAG, "cmp: post-test session-like procs: " + remaining.joinToString(" || "))
        assertTrue("cmp: teardown left UNOWNED session processes (not in foreign snapshot): " +
            unowned.joinToString(" || "), unowned.isEmpty())
    }

    private fun runArm(ctx: android.content.Context, rootfs: File, name: String, display: String,
                       bindX: Boolean, geometry: String?): ArmResult {
        File(rootfs, "root/cmp-session-$name.sh").writeText(cmpScript(display))
        val log = File(ctx.filesDir, "cmp-$name.log")
        if (log.isFile) log.delete()
        val proc = launchGuestProcess(ctx, rootfs, "session-$name", display, bindX, log,
            env = emptyMap(), guestCmd = listOf("/bin/sh", "/root/cmp-session-$name.sh"))
        val rootIdent = pidOfProcess(proc)?.let { identityOf(it) }
        Log.i(TAG, "cmp: arm=$name session root identity: $rootIdent")

        var wins = 0
        // 600s: run 41052060565 showed wins=1/3, mode=bare-shell-fallback,
        // proc alive at the old 240s budget - a SLOW cold start (instr1's
        // full cold flow measured 460s on this emulator), not a structural
        // wrong session; harness launch-watch uses the same 600s bound.
        val readyDeadline = System.currentTimeMillis() + 600_000
        while (System.currentTimeMillis() < readyDeadline) {
            wins = guest(ctx, rootfs, display,
                "xwininfo -root -tree 2>/dev/null | grep -ciE 'gnome.shell|gnome-shell' || true",
                bindX).trim().toIntOrNull() ?: 0
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
        assertTrue("cmp: arm=$name session process died before readiness - environment/fixture " +
            "gap, not comparison data", proc.isAlive)
        assertTrue("cmp: arm=$name never reached the shell-window readiness proxy - " +
            "environment/fixture gap, not comparison data", wins >= 3)

        if (geometry == null) {
            lastLorieGeometry = guest(ctx, rootfs, display, "xdpyinfo | grep dimensions:", true)
                .replace(Regex(".*dimensions:\\s*"), "").replace(Regex("\\s+pixels.*"), "").trim()
            Log.i(TAG, "cmp: lorie geometry=$lastLorieGeometry")
        }

        val steps = linkedMapOf<String, Long>()
        fun step(label: String, body: () -> Unit) {
            val t0 = System.currentTimeMillis(); body(); steps[label] = System.currentTimeMillis() - t0
        }
        step("open-files") {
            // nautilus as a TRACKED foreground process (owned, destroyable).
            val bus = logText().lineSequence()
                .filter { it.contains("gnome-launch: session bus ") }.lastOrNull()
                ?.substringAfter("gnome-launch: session bus ")?.trim()
            val nautLog = File(ctx.filesDir, "cmp-nautilus-$name.log")
            val naut = launchGuestProcess(ctx, rootfs, "nautilus-$name", display, bindX, nautLog,
                env = if (bus != null) mapOf("DBUS_SESSION_BUS_ADDRESS" to bus) else emptyMap(),
                guestCmd = listOf("/usr/bin/nautilus"))
            val dl = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < dl) {
                val n = guest(ctx, rootfs, display,
                    "xwininfo -root -tree | grep -ci nautilus || true", bindX).trim().toIntOrNull() ?: 0
                if (n >= 1 || !naut.isAlive) break
                Thread.sleep(3_000)
            }
            Log.i(TAG, "cmp: arm=$name nautilus alive=${naut.isAlive}")
        }
        step("scroll-x5") { repeat(5) {
            guest(ctx, rootfs, display, "xdotool key Down", bindX); Thread.sleep(1_000) } }
        step("overview-toggle") {
            guest(ctx, rootfs, display, "xdotool key super", bindX); Thread.sleep(3_000)
            guest(ctx, rootfs, display, "xdotool key super", bindX); Thread.sleep(3_000)
        }

        val glx = guest(ctx, rootfs, display, "glxinfo -B 2>&1 | head -25", bindX)
        val fps = logText().lines().filter { it.contains("fps", true) || it.contains("Frame") }
            .takeLast(10).joinToString(" | ")

        // Verified teardown: destroy tracked procs for THIS arm, then walk
        // the captured root tree with per-pid identity revalidation.
        trackedProcs.filter { it.first.endsWith("-$name") }.forEach { (n, p) ->
            try { p.destroy() } catch (t: Throwable) { }
            Log.i(TAG, "cmp: arm=$name destroyed tracked $n (alive=${p.isAlive})")
        }
        trackedProcs.removeIf { it.first.endsWith("-$name") }
        Thread.sleep(2_000)
        if (rootIdent != null) {
            val idents = treeOf(rootIdent.pid).mapNotNull { identityOf(it) }
            val left = killVerified(idents, "arm-$name-teardown")
            if (left.isNotEmpty()) Log.w(TAG, "cmp: arm=$name teardown skipped/reused pids: $left")
        }
        guest(ctx, rootfs, null, "rm -f /root/cmp-session-$name.sh")
        Log.i(TAG, "cmp: arm=$name teardown done")
        return ArmResult(name, mode, wins, steps, glx, fps)
    }
}
