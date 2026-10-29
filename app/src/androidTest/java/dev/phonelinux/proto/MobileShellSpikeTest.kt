package dev.phonelinux.proto

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Mobile desktop mode lane (dispatch-only, mobile-desktop.yml): provision
 * the touch-sized reused-jwm session on the Ubuntu rootfs, launch it
 * against the embedded X server, capture early + settled screenshots for
 * pixel inspection, prove a client window mapped on :0, then tear the
 * whole session tree down by host parentage and verify it is empty
 * (fail closed).
 *
 * Runs alone in its own emulator job: it owns :0 for the whole run, so no
 * GNOME/Alpine session can be racing the display. A green test means the
 * mobile session launched/rendered/tore down in the x86_64 emulator -
 * never ARM or device qualification.
 */
@RunWith(AndroidJUnit4::class)
class MobileShellSpikeTest {
    private val TAG = "PHONELINUX_MOBILE"

    @Test
    fun mobileShellSessionStartsAndTearsDown(): Unit = runBlocking {
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext
        val device = UiDevice.getInstance(instr)
        val d = Distros.UBUNTU
        val installer = DistroInstaller(ctx)

        if (!installer.isInstalled(d)) {
            var last: InstallState = InstallState.NotInstalled
            val ok = installer.install(d) { last = it }
            assertTrue("ubuntu install failed at state=$last", ok)
        }
        assertTrue(installer.isInstalled(d))
        val rootfs = installer.rootfsDir(d)

        // Guest sanity: right distro, apt present (same as the GNOME lane).
        val g = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
            "uname -m; . /etc/os-release; echo \"os=\${NAME}-\${VERSION_ID}\""))
        Log.i(TAG, "guest sanity exit=${g.exitCode} out=${g.stdout.trim()}")
        assertTrue("guest exec must exit 0. stderr=${g.stderr}", g.exitCode == 0)
        assertTrue("guest must be Ubuntu, got: ${g.stdout}", g.stdout.contains("Ubuntu"))

        // Provision the mobile set (bounded: jwm + xterm + pcmanfm + utils).
        if (!GuestMobileShell.hasMobileShell(rootfs)) {
            val r = GuestMobileShell.provisionMobileShell(ctx, rootfs)
            Log.i(TAG, "provision exit=${r.exitCode} tail=${r.stdout.takeLast(800)} errtail=${r.stderr.takeLast(400)}")
            assertTrue("mobile provision failed exit=${r.exitCode}: ${r.stdout.takeLast(400)}", r.exitCode == 0)
        }
        assertTrue("jwm binary present", GuestMobileShell.hasMobileShell(rootfs))

        // Dedicated lane: the fresh emulator owns :0 for the whole run.
        XServer.start(ctx)
        val proc = GuestMobileShell.launchMobileSession(ctx, rootfs)

        // Evidence window: poll mobile-desktop.log for launch markers.
        val logFile = File(ctx.filesDir, "mobile-desktop.log")
        val deadline = System.currentTimeMillis() + 600_000
        var sawLaunchMarker = false
        var sawAlive = false
        while (System.currentTimeMillis() < deadline) {
            val text = if (logFile.isFile) logFile.readText() else ""
            if (text.contains("mobile-launch:")) sawLaunchMarker = true
            if (text.contains("mobile-launch: jwm alive")) { sawAlive = true; break }
            // Decisive failures end the poll immediately (no blind waiting).
            if (text.contains("mobile-launch: jwm died") ||
                text.contains("Cannot open display") ||
                text.contains("jwm: not found")) {
                Log.i(TAG, "decisive failure marker in mobile log; failing fast")
                break
            }
            if (!proc.isAlive) break
            Thread.sleep(10_000)
        }
        Log.i(TAG, "mobile log tail: " + (if (logFile.isFile) logFile.readText().takeLast(1500) else "<missing>"))
        assertTrue("session script never reached the launch marker", sawLaunchMarker)
        assertTrue("jwm never reported alive", sawAlive)
        assertTrue("mobile session process died before the evidence window closed", proc.isAlive)

        // Render evidence: X surface early + settled (pixel judgment is on
        // the artifact screenshots, same convention as the GNOME lane).
        ctx.startActivity(
            Intent(ctx, com.termux.x11.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(30_000)
        Shot.dismissSystemDialogs(device)
        Shot.save("40-mobile-desktop-early")
        Thread.sleep(90_000)
        Shot.dismissSystemDialogs(device)
        Shot.save("41-mobile-desktop-settled")
        assertTrue("mobile session died during the render window", proc.isAlive)

        // Client-window evidence via the guest's own xwininfo against :0.
        val wins = GuestDesktop.guestShell(ctx, rootfs,
            "DISPLAY=:0 xwininfo -root -children 2>/dev/null | grep -i -e xterm -e pcmanfm || true",
            extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"))
        Log.i(TAG, "mobile windows: $wins")
        assertTrue("no xterm window mapped on :0", wins.contains("xterm"))

        // TEARDOWN PROOF: host-parentage kill walk from the tracked proot
        // root (TERM, re-verify, KILL survivors, verify empty). A
        // guest-global PLINUX_SESSION scan is unsafe: /proc is host-bound
        // into every proot and the GNOME/Alpine lanes share one tag.
        val rootPid = hostPidOf(proc)
        assertTrue("proot root PID unreadable - cannot prove teardown", rootPid != null)
        val left = killTreeVerified(rootPid!!)
        assertTrue("session tree not empty after teardown: $left", left.isEmpty())
        Log.i(TAG, "MOBILE-SHELL-OK session alive, rendered, window mapped, tree torn down")
    }

    // ---- host process helpers (temporary, until the GNOME worker's
    // XServer.stopDesktopMode lands and owns this walk) ----

    private fun hostPidOf(p: Process): Int? = try {
        val f = p.javaClass.getDeclaredField("pid"); f.isAccessible = true; f.getInt(p)
    } catch (t: Throwable) { Log.e(TAG, "pid reflection failed", t); null }

    private class PInfo(val ppid: Int, val start: String)

    private fun scanProc(): Map<Int, PInfo> {
        val out = HashMap<Int, PInfo>()
        val dir = File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) } ?: return out
        for (f in dir) {
            try {
                val stat = File(f, "stat").readText()
                val close = stat.lastIndexOf(')')
                if (close < 0) continue
                val rest = stat.substring(close + 1).trim().split(Regex("\\s+"))
                // rest[0]=state(3), rest[1]=ppid(4), rest[19]=starttime(22)
                if (rest.size < 20) continue
                out[f.name.toInt()] = PInfo(rest[1].toInt(), rest[19])
            } catch (t: Throwable) { /* process vanished mid-scan */ }
        }
        return out
    }

    private fun treeOf(root: Int, all: Map<Int, PInfo>): Set<Int> {
        val kids = HashMap<Int, MutableList<Int>>()
        for ((pid, info) in all) kids.getOrPut(info.ppid) { mutableListOf() }.add(pid)
        val out = LinkedHashSet<Int>()
        var frontier = listOf(root)
        while (frontier.isNotEmpty()) {
            out.addAll(frontier)
            frontier = frontier.flatMap { kids[it] ?: emptyList() }.filter { it !in out }
        }
        return out
    }

    /** TERM the snapshot tree, verify, KILL survivors, verify empty.
     *  PID reuse is guarded by starttime. Anything that escaped the
     *  snapshot (reparented) is reported as a leftover and fails the
     *  assert - fail closed, never claim a clean kill we did not verify. */
    private fun killTreeVerified(root: Int): List<Int> {
        val all0 = scanProc()
        val snapshot = treeOf(root, all0)
        val starts = HashMap<Int, String>()
        for (pid in snapshot) all0[pid]?.let { starts[pid] = it.start }
        for (sig in listOf(android.system.OsConstants.SIGTERM, android.system.OsConstants.SIGKILL)) {
            for (pid in snapshot) {
                try { android.os.Process.sendSignal(pid, sig) } catch (t: Throwable) { }
            }
            Thread.sleep(if (sig == android.system.OsConstants.SIGTERM) 3_000 else 2_000)
            if (survivors(snapshot, starts).isEmpty()) return emptyList()
        }
        Thread.sleep(2_000)
        return survivors(snapshot, starts)
    }

    private fun survivors(snapshot: Set<Int>, starts: Map<Int, String>): List<Int> {
        val now = scanProc()
        return snapshot.filter { pid -> now[pid]?.let { starts[pid] == it.start } == true }
    }
}
