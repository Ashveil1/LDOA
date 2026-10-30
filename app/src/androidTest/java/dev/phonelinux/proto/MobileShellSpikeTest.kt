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

        // /dev/tty matrix (xterm Error 14 = spawn open("/dev/tty", O_RDWR)
        // -> ENOSYS(38) in the SESSION context, run 40898320897/40902982729;
        // the dash pre-X probe returned ENXIO - the errno xterm TOLERATES
        // (main.c:3266-3300, tolerated set ENXIO/EIO/ENOENT/ENODEV/EINVAL/
        // ENOTTY/EACCES; ENOSYS excluded). Same noble glibc on both sides
        // and fake_id0 passes non-creating opens through untouched
        // (termux/proot fake_id0/open.c: no metafile + no O_CREAT ->
        // return 0), so source alone does NOT explain the errno
        // difference: trace the ACTUAL binary in the ACTUAL context
        // (fakeRoot -0 like the session, same binds) against controls.
        // No guest strace: a process cannot have two ptracers and proot
        // already traces the guest (proot-me/proot#9); PROOT_VERBOSE
        // (termux/proot cli.c:467-474) is the honest trace at the proot
        // layer. NOTE: a failed redirect on a dash SPECIAL builtin aborts
        // the whole -c script (run 40902982729 killed its own controls),
        // so every probe open runs in a subshell.
        val dashNo0 = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
            "( : < /dev/tty ) 2>/tmp/e1; echo 'dash_no0 rc='${'$'}? 'err='${'$'}(cat /tmp/e1); " +
            "grep TracerPid /proc/self/status; ls -la /dev/tty 2>&1; ls /dev/pts 2>&1"))
        Log.i(TAG, "devtty dash no0: " + dashNo0.stdout.trim())
        val dashF0 = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
            "( : < /dev/tty ) 2>/tmp/e2; echo 'dash_f0 rc='${'$'}? 'err='${'$'}(cat /tmp/e2)"),
            fakeRoot = true)
        Log.i(TAG, "devtty dash fake0: " + dashF0.stdout.trim())

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

        // xterm cells need a live display: wait for the guest-visible X
        // connection first (same xset q semantics as the session gate),
        // then run the actual failing binary in the session's context
        // (fakeRoot -0, X socket bind), quiet and PROOT_VERBOSE=5. The
        // verbose cell's proot stderr is the syscall-disposition evidence
        // (what proot saw and returned), kept to tty/ENOSYS lines + tail.
        val xtermBind = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix")
        // Full verbose stderr of the xterm cell lands here (dumped in the
        // finally block); declared before the probe so finally can see it.
        val devttyTrace = File(ctx.filesDir, "devtty-trace.log")
        var xUp = false
        val xDeadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < xDeadline) {
            if (GuestDesktop.guestOk(ctx, rootfs, "DISPLAY=:0 xset q")) { xUp = true; break }
            Thread.sleep(2_000)
        }
        Log.i(TAG, "devtty xterm probe X readiness: " + xUp)
        if (xUp) {
            // `timeout 20s` bounds runGuest: a hanging xterm cell must not
            // block the session (and the artifact dump) indefinitely.
            // (publish-side fix; 124 = timed out, distinct from xterm's
            // own failure text.)
            val xt = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
                "DISPLAY=:0 timeout 20s xterm -e /bin/true 2>&1 | head -6"),
                extraBinds = xtermBind, fakeRoot = true)
            Log.i(TAG, "devtty xterm fake0: " + xt.stdout.trim())
            val xtv = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
                "DISPLAY=:0 timeout 20s xterm -e /bin/true 2>&1 | head -6"),
                extraBinds = xtermBind, fakeRoot = true,
                extraEnv = mapOf("PROOT_VERBOSE" to "5"))
            // (iteration 3 after run 40911779872): /dev/tty-string
            // windows CANNOT catch the syscall result. The vendored
            // x86_64 libproot.so verbose format (strings-verified in the
            // shipped binary) prints syscall args as raw pointers:
            //   vpid %lu: %s: %s(0x%lx, ...) = 0x%lx [0x%lx, %d]
            // so the only lines literally containing "/dev/tty" are the
            // path-translation markers (vpid %lu: -> "%s"). Correlate by
            // vpid instead: collect the vpids from those markers, then
            // keep EVERY line of those vpids - entry AND result/errno,
            // immune to interleaving from other vpids and to the old
            // "exited with status" windows eating the cap. The full
            // stderr also lands in devttyTrace and streams out in the
            // finally block, so evidence survives even if this parse
            // misses.
            devttyTrace.writeText(xtv.stderr)
            val ttyVpids = xtv.stderr.lines().mapNotNull { l ->
                if (!l.contains("/dev/tty")) null
                else Regex("vpid ([0-9]+):").find(l)?.groupValues?.get(1)
            }.toSet()
            Log.i(TAG, "devtty trace vpids: " + ttyVpids)
            Log.i(TAG, "devtty xterm fake0 verbose: " + xtv.stdout.trim())
            val trace = xtv.stderr.lines().filter { l ->
                ttyVpids.any { v -> l.contains("vpid " + v + ":") }
            }.take(120).joinToString("\n")
            val traceChunks = trace.chunked(3000)
            traceChunks.forEachIndexed { ci, chunk ->
                Log.i(TAG, "devtty trace[" + ci + "/" + traceChunks.size + "]: " + chunk)
            }
            // Gate-1 ctty matrix (approved test-only - NO product
            // wiring): does a controlling terminal make xterm's
            // /dev/tty open SUCCEED, bypassing the alarm-clobbered
            // errno path (xterm-390 main.c:4050-4052, errno read
            // unsaved at ~4081; alarm blocked by Android's app seccomp
            // policy, ENOSYS injected by proot's default case)?
            // `script` (util-linux, noble bsdutils from util-linux
            // 2.39.3-9ubuntu6; -c/-e/-q semantics per man script(1))
            // allocates a pty and gives its child a ctty. Every cell
            // is timeout-bounded (124 = timed out, distinct from app
            // output). PRE-FLIGHT checks presence/infra empirically -
            // rootfs contents are verified, not assumed.
            // FALSIFIER: ctty confirmed (tty/pts evidence) but xterm
            // still dies Error 14 -> the diagnosis has a gap; STOP, no
            // blind iteration.
            // NOT claimed here: teardown/parentage coverage of the
            // added script layer and line-discipline effects on logs
            // (both are Gate-2 verification requirements, unsettled),
            // and no usable-window or session-level property - cell
            // outputs are exit/start facts plus ONE mapped-window
            // observation only.
            val preflight = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
                "command -v script || echo NO_SCRIPT; ls -la /dev/ptmx 2>&1; ls /dev/pts 2>&1 | head -3"),
                extraBinds = xtermBind, fakeRoot = true)
            Log.i(TAG, "devtty ctty preflight: " + preflight.stdout.trim())
            val dashCtty = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
                "timeout 20s script -qec '( : < /dev/tty ) 2>/tmp/e3; echo dash_ctty_rc=${'$'}?; cat /tmp/e3; echo ctty_tty=${'$'}(tty); echo ctty_fd0=${'$'}(readlink /proc/self/fd/0)' /dev/null 2>&1; echo script_rc=${'$'}?"),
                extraBinds = xtermBind, fakeRoot = true)
            Log.i(TAG, "devtty ctty dash: " + dashCtty.stdout.trim())
            val xtermCtty = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
                "DISPLAY=:0 timeout 20s script -qec 'xterm -e /bin/true' /dev/null 2>&1; echo xterm_ctty_rc=${'$'}?"),
                extraBinds = xtermBind, fakeRoot = true)
            Log.i(TAG, "devtty ctty xterm: " + xtermCtty.stdout.trim())
            // Mapped-window observation (still NOT usability proof):
            // a 6-second-lived xterm under a ctty, one xwininfo count
            // mid-life, all inside the outer timeout.
            val xtermMap = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
                "DISPLAY=:0 timeout 25s script -qec 'xterm -e sleep 6 & sleep 3; xwininfo -root -children 2>/dev/null | grep -ci xterm; wait' /dev/null 2>&1; echo xterm_map_rc=${'$'}?"),
                extraBinds = xtermBind, fakeRoot = true)
            Log.i(TAG, "devtty ctty xterm map: " + xtermMap.stdout.trim())
        }
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
        val priorRoots = mutableListOf<Pair<Int, String>>()
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
            // GATE-2 reentry identity: each round's proot root
            // (pid, starttime) must be NEW and every prior round's root
            // DEAD - freshness by identity, never by pts number.
            val roundRoot = hostPidOf(proc)
            assertTrue("proot root PID unreadable (scale=$scale)", roundRoot != null)
            val rr = roundRoot!!
            val rootStart = scanProc()[rr]?.start
            assertTrue("proot root starttime unreadable (scale=$scale)", rootStart != null)
            for (prev in priorRoots) {
                val cur = identityOf(prev.first)
                assertTrue("gate2 reentry: prior round root pid=" + prev.first + " still alive with same start (scale=$scale)", cur == null || cur.start != prev.second)
            }
            assertTrue("gate2 reentry: proot root identity reused across rounds (scale=$scale)", priorRoots.none { it.first == rr && it.second == rootStart })
            priorRoots.add(Pair(rr, rootStart!!))
            if (priorRoots.size == 1) {
                Log.i(TAG, "gate2 line-discipline: CR chars in session log so far: " + logFile.readText().count { it == '\r' })
            }

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

            // GATE-2 lifecycle/ctty evidence (asserted every round):
            // guard exec's, so the proot root's child IS script; the
            // session shell leads its session (sid==pid, setsid proof);
            // jwm/xterm inherit the pty ctty (tty_nr != 0). Guest-side
            // mirrors are the session-shell/jwm/xterm ctty lines echoed
            // by mobile-session.sh.
            val treeDets = treeOf(rr, scanProc()).mapNotNull { procDetail(it) }
            Log.i(TAG, "gate2 tree (scale=$scale): " + treeDets.joinToString { d -> d.comm + "[" + d.pid + "] ppid=" + d.ppid + " sid=" + d.sid + " tty=" + d.ttyNr })
            val scriptDet = treeDets.singleOrNull { it.comm == "script" }
            assertTrue("gate2: expected exactly one script layer as proot root's child (scale=$scale): " + treeDets.filter { it.comm == "script" }, scriptDet != null && scriptDet.ppid == rr)
            // Selector must discriminate the SESSION shell from terminal
            // subshells: xterm setsid's its child shell, so a bare
            // sid==pid match finds TWO leaders (run 41001661701:
            // sh[15418] ppid=script vs sh[15463] ppid=xterm) and
            // singleOrNull false-fails. The session shell is the leader
            // that is script's DIRECT child; the ppid assert below stays.
            val shellDet = treeDets.singleOrNull { it.comm == "sh" && it.sid == it.pid && scriptDet != null && it.ppid == scriptDet.pid }
            assertTrue("gate2: session shell missing or not a session leader (scale=$scale)", shellDet != null)
            assertTrue("gate2: session shell is not script's child (scale=$scale)", shellDet != null && scriptDet != null && shellDet.ppid == scriptDet.pid)
            for (c in listOf("jwm", "xterm")) {
                val d = treeDets.singleOrNull { it.comm == c }
                assertTrue("gate2: " + c + " missing from owned tree (scale=$scale)", d != null)
                assertTrue("gate2: " + c + " has no controlling tty (scale=$scale)", d != null && d.ttyNr != 0)
            }

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

        // GATE-2 abnormal-death verification (dedicated sessions,
        // destroyed by design). forward = session shell killed abruptly
        // (script must exit on child death); inverse = script killed
        // abruptly (master close SIGHUPs the session). ORPHAN RULE: any
        // captured owned identity (tree + session bus) surviving its
        // cell = orphan risk MATERIALIZED - evidence logged, survivors
        // cleaned via captured identities, and the test FAILS (fail
        // closed; never pass by omission).
        for (cell in listOf("forward", "inverse")) {
            val proc = GuestMobileShell.launchMobileSession(ctx, rootfs)
            logOffset = awaitJwmAlive(proc, logFile, logOffset)
            Thread.sleep(20_000) // pcmanfm/xterm launch before capture
            val rootPid = hostPidOf(proc)
            assertTrue("gate2 " + cell + ": proot root unreadable", rootPid != null)
            val busIdentity = File(rootfs, "root/.mobile-bus.pid")
                .takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
                ?.let { identityOf(it) }
            val tree = treeOf(rootPid!!, scanProc())
            val idents = tree.mapNotNull { identityOf(it) }
            val scriptId = tree.singleOrNull { procDetail(it)?.comm == "script" }?.let { identityOf(it) }
            val shellId = tree.singleOrNull { p -> val d = procDetail(p); d != null && d.comm == "sh" && d.sid == d.pid && scriptId != null && d.ppid == scriptId.pid }?.let { identityOf(it) }
            assertTrue("gate2 " + cell + ": script identity missing", scriptId != null)
            assertTrue("gate2 " + cell + ": session-shell identity missing", shellId != null)
            val victim = if (cell == "forward") shellId!! else scriptId!!
            assertTrue("gate2 " + cell + ": victim identity stale before signal", signalVerified(victim, android.system.OsConstants.SIGKILL))
            val died = awaitProcessDeath(proc, 30_000)
            assertTrue("gate2 " + cell + ": tracked proot root alive 30s after " + (if (cell == "forward") "session-shell" else "script") + " death", died)
            Thread.sleep(5_000) // SIGHUP cascade grace
            val orphans = (idents + listOfNotNull(busIdentity)).filter { id ->
                val cur = identityOf(id.pid); cur != null && cur.start == id.start
            }
            if (orphans.isNotEmpty()) {
                Log.e(TAG, "gate2 " + cell + ": ORPHANS survived: " + orphans.joinToString { o -> o.pid.toString() + "(" + (procDetail(o.pid)?.comm ?: "?") + ")" })
                for (o in orphans) { signalVerified(o, android.system.OsConstants.SIGTERM) }
                Thread.sleep(2_000)
                for (o in orphans) { signalVerified(o, android.system.OsConstants.SIGKILL) }
            }
            assertTrue("gate2 " + cell + ": owned identities survived abnormal death - ORPHAN RISK MATERIALIZED: " + orphans.map { it.pid }, orphans.isEmpty())
        }
        } finally {
            dumpLogTail("mobile-desktop.log", logFile)
            dumpLogTail("xserver.log", File(ctx.filesDir, "xserver.log"))
            dumpLogTail("devtty-trace.log", devttyTrace, 262144)
        }
        MobileModeSettings.write(ctx, defaultPrefs)
        Log.i(TAG, "MOBILE-SHELL-OK sessions alive under ctty wrap, rendered at 3 scales, windows mapped, trees torn down clean, gate2 lifecycle/reentry/abnormal-death verified")
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
        // Numbered chunks: logd drops are otherwise undetectable (run
        // 40917499338 reassembly came up 1536B short of the on-device size
        // with no way to localize the gap).
        val dumpChunks = text.chunked(3000)
        dumpChunks.forEachIndexed { i, c ->
            Log.i(TAG, name + " dump[" + i + "/" + dumpChunks.size + "]: " + c)
            Thread.sleep(15)
        }
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
                text.contains("mobile-launch: script helper missing") ||
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

    /** Host-side detail for one pid: comm, session id, ctty device.
     *  stat fields after ')': 0=state(3) 1=ppid(4) 2=pgrp(5) 3=session(6)
     *  4=tty_nr(7) ... 19=starttime(22). ttyNr 0 = no controlling tty. */
    private class PDet(val pid: Int, val ppid: Int, val comm: String, val sid: Int, val ttyNr: Int, val start: String)

    private fun procDetail(pid: Int): PDet? = try {
        val stat = File("/proc/" + pid + "/stat").readText()
        val close = stat.lastIndexOf(')')
        if (close < 0) null else {
            val comm = stat.substring(stat.indexOf('(') + 1, close)
            val rest = stat.substring(close + 1).trim().split(Regex("\\s+"))
            if (rest.size < 20) null
            else PDet(pid, rest[1].toInt(), comm, rest[3].toInt(), rest[4].toInt(), rest[19])
        }
    } catch (t: Throwable) { null }

    /** Signal one captured identity ONLY after re-verifying its
     *  (pid, starttime, uid) - a reused PID is never signaled. Returns
     *  false when the identity is stale or the signal failed. */
    private fun signalVerified(id: PIdent, sig: Int): Boolean {
        val cur = identityOf(id.pid)
        if (cur == null || cur.start != id.start || cur.uid != id.uid) return false
        return try { android.os.Process.sendSignal(id.pid, sig); true } catch (t: Throwable) { false }
    }

    private fun awaitProcessDeath(proc: Process, ms: Long): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) return true
            Thread.sleep(1_000)
        }
        return !proc.isAlive
    }

    private fun survivors(idents: Map<Int, PIdent>): List<Int> {
        return idents.values.filter { id ->
            val cur = identityOf(id.pid)
            cur != null && cur.start == id.start && cur.uid == id.uid
        }.map { it.pid }
    }
}
