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
 * against the embedded X server, capture screenshots for pixel inspection
 * at the DEFAULT, MIN (75%) and MAX (200%) UI scales (readability of the
 * settings range is judged on the artifacts, not asserted), prove a
 * client window mapped on :0, and after EVERY launch tear the whole
 * session tree down by host parentage and verify it is empty
 * (fail closed - a leftover fails the test).
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

        // Bounded /dev/tty probe (xterm Error 14 = ERROR_OPDEVTTY, errno 38
        // ENOSYS, run 40898320897). NO guest strace: a process cannot have
        // two ptracers and proot already traces the guest
        // (github.com/proot-me/proot/issues/9), so a guest strace would be
        // an altered environment, not product evidence. Instead: dash
        // opens /dev/tty in xterm's exact mode (O_RDWR via the POSIX <>
        // operator, error.h: ERROR_OPDEVTTY = spawn/startup open of
        // /dev/tty), read-only + /dev/null controls, and the proot layer's
        // own view via PROOT_VERBOSE (termux/proot cli.c:467-474 honors
        // the env var; the vendored libproot.so is the Termux build).
        // (publish-side fixes by the app worker, behavior-preserving:
        //  the patch's nested double quotes did not compile in Kotlin -
        //  switched the echo wrappers to single quotes; and `head -c 1
        //  /dev/tty` would BLOCK forever if the open succeeded (tty read
        //  with no input) - replaced with a pure O_RDONLY open test via
        //  the POSIX `:` builtin, matching the open-only control intent.)
        val ttyProbe = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
            ": <> /dev/tty 2>/tmp/ttyrw.err; echo 'dev_tty_rdwr rc='${'$'}? 'err='${'$'}(cat /tmp/ttyrw.err); " +
            ": < /dev/tty 2>/tmp/ttyr.err; echo 'dev_tty_rd rc='${'$'}? 'err='${'$'}(cat /tmp/ttyr.err); " +
            "head -c 1 /dev/null >/dev/null 2>&1; echo 'dev_null_rd rc='${'$'}?; " +
            "grep TracerPid /proc/self/status; " +
            "ls -la /dev/tty 2>&1; ls /dev/pts 2>&1"),
            extraEnv = mapOf("PROOT_VERBOSE" to "5"))
        Log.i(TAG, "devtty probe exit=" + ttyProbe.exitCode +
            " stdout: " + ttyProbe.stdout.trim() +
            " proot-stderr tail: " + ttyProbe.stderr.takeLast(4000))

        // Provision the mobile set (bounded: jwm + xterm + pcmanfm + utils).
        if (!GuestMobileShell.hasMobileShell(rootfs)) {
            val r = GuestMobileShell.provisionMobileShell(ctx, rootfs)
            Log.i(TAG, "provision exit=${r.exitCode} tail=${r.stdout.takeLast(800)} errtail=${r.stderr.takeLast(400)}")
            assertTrue("mobile provision failed exit=${r.exitCode}: ${r.stdout.takeLast(400)}", r.exitCode == 0)
        }
        assertTrue("jwm binary present", GuestMobileShell.hasMobileShell(rootfs))

        // Dedicated lane: the fresh emulator owns :0 for the whole run.
        // XServer.start is fire-and-forget: log the ACTUAL X server process
        // (vendored lorie runs as app_process com.termux.x11.CmdEntryPoint)
        // so a missing/dying X is visible in the evidence; the guest script
        // gates on a real xset q connection before any X client runs.
        XServer.start(ctx)
        Log.i(TAG, "xserver pids at launch: " + xServerPids())
        val logFile = File(ctx.filesDir, "mobile-desktop.log")
        val defaultPrefs = MobileModeSettings.read(ctx)
        var logOffset = 0L

        // One full launch->evidence->verified-teardown cycle per scale.
        // Default gets early+settled shots and the xwininfo window proof;
        // min/max add one settled shot each for readability pixel review.
        // try/finally: whatever happens below, the device-side session and
        // X server logs stream to logcat (captured by the workflow) BEFORE
        // the test task uninstalls the app - run 40892838123 lost both
        // logs to that uninstall and the failure was undiagnosable.
        try {
        for (round in listOf(
            Triple(defaultPrefs.scale, "40-mobile-desktop-early", "41-mobile-desktop-settled"),
            Triple(0.75f, "", "42-mobile-scale-min"),
            Triple(2.0f, "", "43-mobile-scale-max"))) {
            val (scale, shotEarly, shotSettled) = round
            MobileModeSettings.write(ctx, defaultPrefs.copy(scale = scale))
            Log.i(TAG, "mobile round scale=$scale")
            val proc = GuestMobileShell.launchMobileSession(ctx, rootfs)
            logOffset = awaitJwmAlive(proc, logFile, logOffset)
            assertTrue("mobile session process died before the evidence window closed (scale=$scale)", proc.isAlive)

            ctx.startActivity(
                Intent(ctx, com.termux.x11.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(30_000)
            Shot.dismissSystemDialogs(device)
            if (shotEarly.isNotEmpty()) Shot.save(shotEarly)
            Thread.sleep(if (shotEarly.isNotEmpty()) 90_000 else 30_000)
            Shot.dismissSystemDialogs(device)
            Shot.save(shotSettled)
            assertTrue("mobile session died during the render window (scale=$scale)", proc.isAlive)

            // Client-window evidence via the guest's own xwininfo on :0.
            // EVERY round: a failed render must never count as a visual pass.
            val wins = GuestDesktop.guestShell(ctx, rootfs,
                "DISPLAY=:0 xwininfo -root -children 2>/dev/null | grep -i -e xterm -e pcmanfm || true",
                extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"))
            Log.i(TAG, "mobile windows (scale=$scale): $wins")
            assertTrue("no xterm window mapped on :0 (scale=$scale)", wins.contains("xterm"))

            // TEARDOWN PROOF (every round): host-parentage kill walk from
            // the tracked proot root PLUS the session bus (dbus-daemon
            // reparents to init and escapes the parentage walk). Identity
            // is captured NOW, at launch-confirmed time - the script writes
            // the pidfile before starting jwm, so jwm-alive implies THIS
            // launch wrote it - because reading starttime at kill time
            // cannot guard a PID reused after the session died. Every
            // signal is re-verified against the captured (starttime, uid)
            // pair, so a stale pidfile or reused PID kills nothing.
            val rootPid = hostPidOf(proc)
            assertTrue("proot root PID unreadable - cannot prove teardown", rootPid != null)
            val busIdentity = File(rootfs, "root/.mobile-bus.pid")
                .takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
                ?.let { identityOf(it) }
            Log.i(TAG, "mobile bus identity: $busIdentity")
            val left = killTreeVerified(rootPid!!, setOfNotNull(busIdentity))
            assertTrue("session tree not empty after teardown (scale=$scale): $left", left.isEmpty())
            Log.i(TAG, "mobile round scale=$scale torn down clean")
        }
        } finally {
            dumpLogTail("mobile-desktop.log", logFile)
            dumpLogTail("xserver.log", File(ctx.filesDir, "xserver.log"))
        }
        MobileModeSettings.write(ctx, defaultPrefs)
        Log.i(TAG, "MOBILE-SHELL-OK sessions alive, rendered at 3 scales, windows mapped, trees torn down")
    }

    /** Stream a bounded tail of a device-side log into logcat with explicit
     *  markers. logcat outlives the app (the post-run uninstall kills
     *  run-as pulls); 3000-char chunks stay under the per-line limit. */
    private fun dumpLogTail(name: String, f: File, maxBytes: Int = 65536) {
        if (!f.isFile) { Log.i(TAG, name + " DUMP: <missing>"); return }
        val len = f.length()
        val skip = (len - maxBytes).coerceAtLeast(0L)
        val text = f.inputStream().use { it.skip(skip); it.readBytes().toString(Charsets.UTF_8) }
        Log.i(TAG, name + " DUMP BEGIN (" + len + "B, last " + text.length + " chars)")
        for (chunk in text.chunked(3000)) Log.i(TAG, chunk)
        Log.i(TAG, name + " DUMP END")
    }

    /** Poll the session log (new bytes only) for the jwm-alive marker with
     *  fail-fast on decisive failures. Returns the new log offset. */
    private fun awaitJwmAlive(proc: Process, logFile: File, startOffset: Long): Long {
        val deadline = System.currentTimeMillis() + 600_000
        var offset = startOffset
        var sawLaunchMarker = false
        while (System.currentTimeMillis() < deadline) {
            val len = if (logFile.isFile) logFile.length() else 0L
            var text = ""
            if (len > offset) {
                logFile.inputStream().use { it.skip(offset); text = it.readBytes().toString(Charsets.UTF_8) }
                offset = len
            }
            if (text.contains("mobile-launch:")) sawLaunchMarker = true
            if (text.contains("mobile-launch: jwm alive")) {
                assertTrue("session script never reached the launch marker before alive", sawLaunchMarker)
                return offset
            }
            if (text.contains("mobile-launch: jwm died") ||
                text.contains("mobile-launch: X :0 rejected") ||
                text.contains("Cannot open display") ||
                text.contains("jwm: not found")) {
                Log.i(TAG, "decisive failure marker in mobile log; failing fast")
                break
            }
            if (!proc.isAlive) break
            Thread.sleep(10_000)
        }
        Log.i(TAG, "mobile log tail: " + (if (logFile.isFile) logFile.readText().takeLast(1500) else "<missing>"))
        Log.i(TAG, "xserver pids at failure: " + xServerPids())
        dumpLogTail("xserver.log", File(logFile.parentFile, "xserver.log"), 32768)
        assertTrue("session script never reached the launch marker", sawLaunchMarker)
        throw AssertionError("jwm never reported alive")
    }

    /** Actual X server process evidence (host side): vendored lorie runs
     *  as app_process com.termux.x11.CmdEntryPoint (XServer.kt:60). Returns
     *  (pid, starttime) pairs so a dying or restarted X is identifiable. */
    private fun xServerPids(): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val dir = File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) } ?: return out
        for (f in dir) {
            try {
                val cmd = File(f, "cmdline").readText().replace('\u0000', ' ')
                if (cmd.contains("com.termux.x11.CmdEntryPoint")) {
                    val stat = File(f, "stat").readText()
                    val close = stat.lastIndexOf(')')
                    val rest = stat.substring(close + 1).trim().split(Regex("\\s+"))
                    if (rest.size >= 20) out.add(f.name.toInt() to rest[19])
                }
            } catch (t: Throwable) { /* process vanished mid-scan */ }
        }
        return out
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

    private class PIdent(val pid: Int, val start: String, val uid: Int)

    /** Identity of one live process, or null if it is gone or NOT ours
     *  (all guest processes run as the app uid). */
    private fun identityOf(pid: Int): PIdent? {
        val info = scanProc()[pid] ?: return null
        val uid = try { android.system.Os.stat("/proc/$pid").st_uid } catch (t: Throwable) { return null }
        if (uid != android.os.Process.myUid()) return null
        return PIdent(pid, info.start, uid)
    }

    /** TERM the snapshot tree (+ explicitly captured identities such as
     *  the reparented session bus), verify, KILL survivors, verify empty.
     *  Before EVERY signal the PID's current (starttime, uid) must match
     *  the identity captured at snapshot/launch time - a reused PID is
     *  never signaled. Leftovers fail the caller's assert: fail closed,
     *  never claim a clean kill we did not verify. */
    private fun killTreeVerified(root: Int, extraIdents: Set<PIdent?> = emptySet()): List<Int> {
        val all0 = scanProc()
        val idents = HashMap<Int, PIdent>()
        for (pid in treeOf(root, all0)) identityOf(pid)?.let { idents[pid] = it }
        for (e in extraIdents) if (e != null) idents[e.pid] = e
        fun signal(sig: Int) {
            for (id in idents.values) {
                val cur = identityOf(id.pid)
                if (cur != null && cur.start == id.start && cur.uid == id.uid) {
                    try { android.os.Process.sendSignal(id.pid, sig) } catch (t: Throwable) { }
                }
            }
        }
        signal(android.system.OsConstants.SIGTERM)
        Thread.sleep(3_000)
        if (survivors(idents).isEmpty()) return emptyList()
        signal(android.system.OsConstants.SIGKILL)
        Thread.sleep(2_000)
        return survivors(idents)
    }

    private fun survivors(idents: Map<Int, PIdent>): List<Int> {
        return idents.values.filter { id ->
            val cur = identityOf(id.pid)
            cur != null && cur.start == id.start && cur.uid == id.uid
        }.map { it.pid }
    }
}
