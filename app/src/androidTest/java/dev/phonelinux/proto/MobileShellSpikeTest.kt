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
        // Floating-path coverage without a session round (pure-function
        // checks): outside maximized mode the window layer emits no jwm
        // rules and xterm keeps its fixed startup geometry. Forced
        // floating copy keeps this independent of stored prefs.
        val floatingPrefs = defaultPrefs.copy(windowMode = "floating")
        assertTrue("floating mode must not emit jwm window rules", GuestMobileWindows.jwmWindowRules(floatingPrefs).isEmpty())
        assertTrue("floating mode must keep fixed xterm geometry", GuestMobileWindows.xtermGeometryArg(floatingPrefs).isNotEmpty())
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
            // Bounded feature exercise: the window-behavior layer
            // (GuestMobileWindows) is opt-in here - the product default
            // stays floating until settings access + runtime proof.
            MobileModeSettings.write(ctx, defaultPrefs.copy(scale = scale, windowMode = "maximized"))
            Log.i(TAG, "mobile round scale=$scale")
            val proc = GuestMobileShell.launchMobileSession(ctx, rootfs)
            logOffset = awaitJwmAlive(proc, logFile, logOffset)
            assertTrue("mobile session process died before the evidence window closed (scale=$scale)", proc.isAlive)
            // Window-layer coverage (deterministic, no pixel calibration):
            // launchMobileSession wrote root/.jwmrc from this round's
            // prefs - maximized Group rule and Home button present, no
            // unreplaced template markers.
            val jwmrcText = File(rootfs, "root/.jwmrc").readText()
            assertTrue("mobile jwmrc missing maximized Group rule (scale=$scale)", jwmrcText.contains("<Option>maximized</Option>"))
            assertTrue("mobile jwmrc missing Home tray button (scale=$scale)", jwmrcText.contains("showdesktop"))
            assertTrue("mobile jwmrc has unreplaced template marker (scale=$scale)", !jwmrcText.contains("__WINDOW_RULES__"))
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
            // -tree, NOT -children: jwm is a REPARENTING WM, so a managed
            // client nests under jwm's (unnamed) frame and a one-level
            // root-children query misses it (run 41006697379: query saw
            // only the unreparented pcmanfm desktop while the settled
            // screenshot, captured ~100ms earlier, showed xterm decorated
            // and mapped). Same recursive pattern as UbuntuGnomeSpikeTest's
            // windowIds.
            val wins = GuestDesktop.guestShell(ctx, rootfs,
                "DISPLAY=:0 xwininfo -root -tree 2>/dev/null | grep -i -e xterm -e pcmanfm || true",
                extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"))
            Log.i(TAG, "mobile windows (scale=$scale): $wins")
            // Case-consistent with the grep -i above: the grep is
            // case-insensitive but Kotlin contains() is not - a line
            // carrying only "XTerm" would pass grep yet fail the assert.
            assertTrue("no xterm window mapped on :0 (scale=$scale)", wins.lowercase().contains("xterm"))
            // WM-state probe (EVIDENCE ONLY, no assert - calibrate first):
            // noble jwm 2.4.3-1build2 leaves ClientState.windowType
            // uninitialized for type-less clients (hint.c ReadWindowState;
            // fixed upstream post-2.4.3), making <Type> group matching
            // nondeterministic (run 41186745767: xterm maximized at scale
            // 1.0, cascaded at 0.75; local Xvfb repro agreed). Dump each
            // client's real _NET_WM_STATE / type / size hints per round.
            val winIds = Regex("""(0x[0-9a-f]+) "(?:xterm|root)"""", RegexOption.IGNORE_CASE)
                .findAll(wins).map { it.groupValues[1] }.distinct().toList()
            for (wid in winIds) {
                val wst = GuestDesktop.guestShell(ctx, rootfs,
                    "DISPLAY=:0 xprop -id $wid _NET_WM_STATE _NET_WM_WINDOW_TYPE WM_NORMAL_HINTS 2>/dev/null || true",
                    extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"))
                Log.i(TAG, "mobile window state (scale=$scale $wid): $wst")
            }

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
            val rootIdent = identityOf(rootPid!!)
            assertTrue("proot root identity unreadable (scale=$scale)", rootIdent != null)
            val left = killTreeVerified(rootIdent, setOfNotNull(busIdentity))
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
            // Capture the root identity ONCE, at launch-confirmed time;
            // the finally below passes THIS - never a re-derived pid.
            val rootIdent = identityOf(rootPid!!)
            assertTrue("gate2 " + cell + ": proot root identity unreadable", rootIdent != null)
            val busIdentity = File(rootfs, "root/.mobile-bus.pid")
                .takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
                ?.let { identityOf(it) }
            try {
            val tree = treeOf(rootPid!!, scanProc())
            val idents = tree.mapNotNull { identityOf(it) }
            val scriptId = tree.singleOrNull { procDetail(it)?.comm == "script" }?.let { identityOf(it) }
            val shellId = tree.singleOrNull { p -> val d = procDetail(p); d != null && d.comm == "sh" && d.sid == d.pid && scriptId != null && d.ppid == scriptId.pid }?.let { identityOf(it) }
            assertTrue("gate2 " + cell + ": script identity missing", scriptId != null)
            assertTrue("gate2 " + cell + ": session-shell identity missing", shellId != null)
            val victim = if (cell == "forward") shellId!! else scriptId!!
            val tSignal = System.currentTimeMillis()
            assertTrue("gate2 " + cell + ": victim identity stale before signal", signalVerified(victim, android.system.OsConstants.SIGKILL))
            Log.i(TAG, "gate2 " + cell + ": victim pid=" + victim.pid + " SIGKILL verified-sent at wall=" + tSignal)
            val died = awaitProcessDeath(proc, 30_000)
            // Victim truth (run 41212895379): forward kills script's
            // DIRECT child sh (the pty session leader, sid==pid); the
            // launch log's "session-shell" is THAT shell's child and
            // survives by design of this cell. Name the real victim.
            assertTrue("gate2 " + cell + ": tracked proot root alive 30s after SIGKILL to " +
                (if (cell == "forward") "script's child session shell" else "the script layer") +
                " (victim pid=" + victim.pid + ")", died)
            Log.i(TAG, "gate2 " + cell + ": root death observed after " + (System.currentTimeMillis() - tSignal) + "ms")
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
            } finally {
                // Fail-closed AND clean: a FAILED cell must not leak its
                // session until suite end (run 41212895379: the forward
                // session's 9 tracees lived 113s past the assert, reaped
                // only by the suite-end uid kill). After a PASS this is
                // a verified no-op: no captured identity resolves alive.
                // A cleanup failure must never mask the primary result
                // (owner review): catch, log, let the primary propagate.
                try {
                    val leaked = killTreeVerified(rootIdent, setOfNotNull(busIdentity))
                    if (leaked.isEmpty()) Log.i(TAG, "gate2 " + cell + ": finally cleanup no-op - all captured identities already reaped")
                    if (leaked.isNotEmpty()) Log.e(TAG, "gate2 " + cell + ": finally reaped leaked identities: " + leaked)
                } catch (t: Throwable) {
                    Log.e(TAG, "gate2 " + cell + ": finally cleanup failed - primary result preserved", t)
                }
            }
        }

        // REENTRY PROOF (user compat goal: startup/exit/reentry). The
        // rounds prove relaunch after a CLEAN teardown; this proves a
        // fresh session comes up and maps its windows AFTER the
        // abnormal-death cells above - abrupt exit must not poison
        // reentry. Teardown is asserted like a round's.
        run {
            val proc = GuestMobileShell.launchMobileSession(ctx, rootfs)
            logOffset = awaitJwmAlive(proc, logFile, logOffset)
            Thread.sleep(20_000) // pcmanfm/xterm launch before capture
            var left: List<Int> = listOf(-1)
            try {
                val wins = GuestDesktop.guestShell(ctx, rootfs,
                    "DISPLAY=:0 xwininfo -root -tree 2>/dev/null | grep -i -e xterm -e pcmanfm || true",
                    extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"))
                Log.i(TAG, "mobile windows (reentry): $wins")
                // Presence in the tree list is NOT a mapping proof (owner
                // review of 7c96b4a): assert the xterm window's actual
                // MapState is IsViewable.
                val wid = Regex("""(0x[0-9a-f]+) "xterm"""", RegexOption.IGNORE_CASE)
                    .find(wins)?.groupValues?.get(1)
                assertTrue("no xterm window id on :0 after abnormal-death reentry", wid != null)
                val mapState = GuestDesktop.guestShell(ctx, rootfs,
                    "DISPLAY=:0 xwininfo -id $wid 2>/dev/null | grep 'Map State' || true",
                    extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"))
                Log.i(TAG, "reentry xterm map state: $mapState")
                assertTrue("xterm not IsViewable after abnormal-death reentry: $mapState", mapState.contains("IsViewable"))
            } finally {
                try {
                    val rIdent = hostPidOf(proc)?.let { identityOf(it) }
                    if (rIdent != null) {
                        val reBus = File(rootfs, "root/.mobile-bus.pid")
                            .takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
                            ?.let { identityOf(it) }
                        left = killTreeVerified(rIdent, setOfNotNull(reBus))
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "reentry cleanup failed - primary result preserved", t)
                }
            }
            assertTrue("reentry teardown unproven (root identity unreadable) or residuals left: $left", left.isEmpty())
        }
        } finally {
            dumpLogTail("mobile-desktop.log", logFile)
            dumpLogTail("xserver.log", File(ctx.filesDir, "xserver.log"))
            dumpLogTail("devtty-trace.log", devttyTrace, 262144)
        }
        MobileModeSettings.write(ctx, defaultPrefs)
        Log.i(TAG, "MOBILE-SHELL-OK sessions alive under ctty wrap, rendered at 3 scales, windows mapped, trees torn down clean, gate2 lifecycle/reentry/abnormal-death verified")
    }

    /** Root-reuse fixture (owner review of 7c96b4a): the walk must skip -
     *  NO signals - when the captured root identity is dead or its pid was
     *  recycled. (b) runs first, on the live tree, with a fabricated
     *  starttime mismatch; (a) then kills the root and reaps with the
     *  stale identity. Both times every child must be UNTOUCHED. Cleanup
     *  reaps by the children's own identities (extras path), which carries
     *  its own per-signal rechecks. */
    @Test
    fun killTreeVerifiedSkipsDeadOrReusedRoot(): Unit = runBlocking {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "sleep 300 & sleep 300; wait"))
        val pid = hostPidOf(p)
        assertTrue("fixture root pid unreadable", pid != null)
        Thread.sleep(1_000) // children up
        val ident = identityOf(pid!!)
        assertTrue("fixture root identity unreadable", ident != null)
        val kids = treeOf(pid, scanProc()).filter { it != pid }
        assertTrue("fixture children missing", kids.size >= 2)
        val kidIdents = kids.mapNotNull { identityOf(it) }.toSet()
        try {
            // (b) recycled root: live pid, wrong starttime -> skip.
            val bogus = PIdent(pid, ident!!.start + "0", ident.uid)
            val leftBogus = killTreeVerified(bogus, emptySet())
            assertTrue("reused-root walk must reap nothing", leftBogus.isEmpty())
            val aliveAfterBogus = kidIdents.count { id -> identityOf(id.pid)?.start == id.start }
            assertTrue("reused-root walk signaled the tree", aliveAfterBogus == kidIdents.size && p.isAlive)
            // (a) dead root: SIGKILL it ourselves, stale identity reap.
            android.os.Process.sendSignal(pid, android.system.OsConstants.SIGKILL)
            p.waitFor()
            Thread.sleep(500)
            val leftDead = killTreeVerified(ident, emptySet())
            assertTrue("dead-root walk must reap nothing", leftDead.isEmpty())
            val aliveAfterDead = kidIdents.count { id -> identityOf(id.pid)?.start == id.start }
            assertTrue("dead-root walk signaled orphaned children", aliveAfterDead == kidIdents.size)
        } finally {
            val left = killTreeVerified(null, kidIdents.map { it as PIdent? }.toSet())
            if (left.isNotEmpty()) Log.e(TAG, "fixture cleanup leftovers: " + left)
            p.destroyForcibly()
        }
    }

    /** GATE-3 input outcome (proposal (c), scope-qualified 2026-11-01):
     *  the two-finger wheel gesture is proven END TO END in the custom
     *  desktop - delivery AND legacy-client outcome - closing the
     *  "xev delivery alone != scroll" gap (GNOME harness arm8 is
     *  delivery-only by design; arm6 is pixel-logged, never asserted).
     *  Sub-cell A (delivery control): a tagged held xev window records
     *  which button (4 = scrollback, 5 = forward) each drag sign emits -
     *  delivery asserted AND the outcome cell's direction calibrated
     *  in-run, never assumed. Sub-cell B (outcome): a test-owned xterm
     *  (-sl 2000 explicit scrollback, -T ldoa-scrolltest) filled with a
     *  deterministic NON-PERIODIC ASCII intensity fingerprint
     *  (k(i) = ((i*2654435761+12345) mod 2^31) mod 41 bar glyphs +
     *  literal index) is scrolled with the calibrated drag; in-process
     *  Bitmap row-luminance correlation over a bounded ROI must show the
     *  content shifted down by >= 1 measured text line, with ambiguity
     *  REJECTED (corr >= 0.95 AND margin >= 0.03 over every candidate
     *  outside +-lh/2, else honest failure - periodic peaks collapsed the
     *  margin to ~0 in local validation 2026-11-01, which is why the
     *  fingerprint is non-periodic). A no-gesture negative control must
     *  pass first (shift <= 2px) or the capture is declared unstable and
     *  the test fails instead of asserting on noise. xev is fully torn
     *  down (window gone) before B so nothing occludes the measured ROI.
     *  Isolated session lifetime; finally reaps by captured identities.
     *  Emulator-only: no smoothness/FPS/ARM/device claim. */
    @Test
    fun mobileTwoFingerScrollOutcome(): Unit = runBlocking {
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
        if (!GuestMobileShell.hasMobileShell(rootfs)) {
            val r = GuestMobileShell.provisionMobileShell(ctx, rootfs)
            Log.i(TAG, "scroll: provision exit=${r.exitCode}")
            assertTrue("mobile provision failed exit=${r.exitCode}", r.exitCode == 0)
        }
        XServer.start(ctx) // idempotent: a live server is reused
        val xtermBind = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix")
        val defaultPrefs = MobileModeSettings.read(ctx)
        val logFile = File(ctx.filesDir, "mobile-desktop.log")
        var logOffset = if (logFile.isFile) logFile.length() else 0L

        fun guest(cmd: String): String = GuestDesktop.guestShell(ctx, rootfs, cmd, extraBinds = xtermBind)

        // X readiness (same xset q semantics as the suite method).
        val xDeadline = System.currentTimeMillis() + 90_000
        var xUp = false
        while (System.currentTimeMillis() < xDeadline) {
            if (GuestDesktop.guestOk(ctx, rootfs, "DISPLAY=:0 xset q")) { xUp = true; break }
            Thread.sleep(2_000)
        }
        assertTrue("scroll: X :0 never became ready", xUp)

        MobileModeSettings.write(ctx, defaultPrefs.copy(scale = 1.0f, windowMode = "maximized"))
        val proc = GuestMobileShell.launchMobileSession(ctx, rootfs)
        logOffset = awaitJwmAlive(proc, logFile, logOffset)
        Thread.sleep(20_000) // session clients map
        val rootPid = hostPidOf(proc)
        assertTrue("scroll: proot root unreadable", rootPid != null)
        val rootIdent = identityOf(rootPid!!)
        assertTrue("scroll: proot root identity unreadable", rootIdent != null)
        val busIdentity = File(rootfs, "root/.mobile-bus.pid")
            .takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            ?.let { identityOf(it) }
        ctx.startActivity(Intent(ctx, com.termux.x11.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(15_000)
        Shot.dismissSystemDialogs(device)

        var xevProc: Process? = null
        var xtermProc: Process? = null
        val heldIdents = HashSet<PIdent?>()
        val xevLog = File(ctx.filesDir, "scroll-xev.log")
        val xtermLog = File(ctx.filesDir, "scroll-xterm.log")
        try {
            // device<->guest affine map from two pointer probes (pattern
            // proven in the GNOME harness). Probes run BEFORE the test
            // xterm maps so the 1px micro-drag cannot touch the measured
            // window's content.
            fun probeX(devX: Int, devY: Int): Pair<Int, Int>? {
                device.swipe(devX, devY, devX + 1, devY, 5)
                Thread.sleep(600)
                val out = guest("DISPLAY=:0 xdotool getmouselocation --shell 2>/dev/null || true")
                val x = Regex("X=(-?\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
                val y = Regex("Y=(-?\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
                return if (x != null && y != null) Pair(x, y) else null
            }
            val p1 = probeX(300, 1200)
            val p2 = probeX(700, 1700)
            Log.i(TAG, "scroll: mapping probes dev(300,1200)->" + p1 + " dev(700,1700)->" + p2)
            assertTrue("scroll: mapping probes failed", p1 != null && p2 != null)
            assertTrue("scroll: probe delta too small for a stable map",
                Math.abs(p2!!.first - p1!!.first) >= 10 && Math.abs(p2.second - p1.second) >= 10)
            fun guestToDevice(gx: Double, gy: Double): Pair<Int, Int> {
                val a = p1!!; val b = p2!!
                val ax = (700 - 300).toDouble() / (b.first - a.first)
                val bx = 300 - ax * a.first
                val ay = (1700 - 1200).toDouble() / (b.second - a.second)
                val by = 1200 - ay * a.second
                return Pair((ax * gx + bx).toInt(), (ay * gy + by).toInt())
            }

            // ---- SUB-CELL A: wheel DELIVERY + direction calibration
            xevLog.delete()
            xevProc = heldGuest(listOf("/usr/bin/xev", "-event", "button"), xevLog)
            heldIdents.add(hostPidOf(xevProc!!)?.let { identityOf(it) })
            var xev: WinGeom? = null
            val xevDeadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < xevDeadline && xev == null) {
                xev = windowGeom(guest("DISPLAY=:0 xwininfo -root -tree 2>/dev/null || true"), "Event Tester")
                if (xev == null) Thread.sleep(2_500)
            }
            assertTrue("scroll: xev window never mapped", xev != null)
            Log.i(TAG, "scroll: xev window " + xev)
            val xevG = xev!!
            assertTrue("scroll: xev window too small to target: " + xevG, xevG.w > 60 && xevG.h > 60)
            val xevCenter = guestToDevice(xevG.x + xevG.w / 2.0, xevG.y + xevG.h / 2.0)
            tap(instr, xevCenter.first.toFloat(), xevCenter.second.toFloat())
            Thread.sleep(1_000)
            var delivered = 0
            var wheelUpDySign = 0
            for (dySign in listOf(1, -1)) {
                val before = if (xevLog.isFile) xevLog.readText() else ""
                twoFingerDrag(instr, xevCenter.first.toFloat(), xevCenter.second.toFloat(), 300f * dySign)
                Thread.sleep(1_500)
                val after = if (xevLog.isFile) xevLog.readText() else ""
                val chunk = after.removePrefix(before)
                val p4 = Regex("ButtonPress event[^\\n]*button 4[^0-9]").findAll(chunk).count()
                val p5 = Regex("ButtonPress event[^\\n]*button 5[^0-9]").findAll(chunk).count()
                delivered += p4 + p5
                Log.i(TAG, "scroll: xev drag dySign=" + dySign + " button4=" + p4 + " button5=" + p5)
                if (p4 > 0 && wheelUpDySign == 0) wheelUpDySign = dySign
            }
            Shot.save("scroll-a-xev")
            assertTrue("scroll: no wheel button delivered to xev for either drag sign", delivered > 0)
            assertTrue("scroll: no drag sign produced button 4 (scrollback) - outcome impossible", wheelUpDySign != 0)
            xevProc!!.destroy()
            Thread.sleep(1_000)
            val xevGoneDeadline = System.currentTimeMillis() + 15_000
            var xevGone = false
            while (System.currentTimeMillis() < xevGoneDeadline) {
                if (windowGeom(guest("DISPLAY=:0 xwininfo -root -tree 2>/dev/null || true"), "Event Tester") == null) {
                    xevGone = true; break
                }
                Thread.sleep(2_000)
            }
            assertTrue("scroll: xev window survived teardown - occlusion risk for the outcome ROI", xevGone)
            Log.i(TAG, "scroll: delivery control OK (delivered=" + delivered + ", wheelUpDySign=" + wheelUpDySign + "), xev reaped and unmapped")

            // ---- SUB-CELL B: scroll OUTCOME on a test-owned legacy client
            xtermLog.delete()
            // dash-safe fingerprint loop: no ${'$'} inside $(( )); bar via
            // a builtin-only char loop (no fork per line under proot).
            val printLoop = "i=0; while [ ${'$'}i -lt 240 ]; do k=${'$'}(( ( (i * 2654435761 + 12345) % 2147483648 ) % 41 )); " +
                "printf 'M%03d ' ${'$'}i; j=0; while [ ${'$'}j -lt ${'$'}k ]; do printf '#'; j=${'$'}((j+1)); done; " +
                "printf '\\n'; i=${'$'}((i+1)); done; exec /bin/sh"
            xtermProc = heldGuest(listOf("/usr/bin/xterm", "-sl", "2000",
                "-fa", "DejaVu Sans Mono", "-fs", "13",
                "-T", "ldoa-scrolltest", "-e", "/bin/sh", "-c", printLoop), xtermLog)
            heldIdents.add(hostPidOf(xtermProc!!)?.let { identityOf(it) })
            var xt: WinGeom? = null
            val xtDeadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < xtDeadline && xt == null) {
                xt = windowGeom(guest("DISPLAY=:0 xwininfo -root -tree 2>/dev/null || true"), "ldoa-scrolltest")
                if (xt == null) Thread.sleep(2_500)
            }
            assertTrue("scroll: test xterm never mapped", xt != null)
            val mapState = guest("DISPLAY=:0 xwininfo -id " + xt!!.id + " 2>/dev/null | grep 'Map State' || true")
            Log.i(TAG, "scroll: test xterm " + xt + " mapState=" + mapState.trim())
            assertTrue("scroll: test xterm not IsViewable: " + mapState, mapState.contains("IsViewable"))
            val xtG = xt!!
            assertTrue("scroll: test xterm geometry implausible (expected maximized): " + xtG,
                xtG.w > 400 && xtG.h > 800)
            Thread.sleep(10_000) // fingerprint print settles

            // Content ROI (guest coords): inside the client window, inset
            // 8px, bottom 196px excluded (prompt + cursor rows; lh <= 120).
            val roiX = xtG.x + 8; val roiY = xtG.y + 8
            val roiW = xtG.w - 16; val roiH = xtG.h - 196
            assertTrue("scroll: ROI degenerate", roiW > 200 && roiH > 400)
            val tl = guestToDevice(roiX.toDouble(), roiY.toDouble())
            val br = guestToDevice((roiX + roiW).toDouble(), (roiY + roiH).toDouble())

            fun shotRoi(): Pair<DoubleArray, android.graphics.Bitmap> {
                val bmp = instr.uiAutomation.takeScreenshot()
                    ?: throw AssertionError("scroll: takeScreenshot null")
                val x0 = tl.first.coerceIn(0, bmp.width - 1)
                val y0 = tl.second.coerceIn(0, bmp.height - 1)
                val x1 = br.first.coerceIn(x0 + 1, bmp.width)
                val y1 = br.second.coerceIn(y0 + 1, bmp.height)
                assertTrue("scroll: ROI outside screenshot bounds", x1 - x0 > 150 && y1 - y0 > 300)
                return Pair(rowBrightness(bmp, x0, y0, x1 - x0, y1 - y0), bmp)
            }

            val (pre, bmpPre) = shotRoi()
            Thread.sleep(3_000)
            val (pre2, bmpPre2) = shotRoi()
            val lhEst = estLineHeight(pre)
            Log.i(TAG, "scroll: measured line height " + lhEst + " device px")
            assertTrue("scroll: line height unmeasurable or implausible: " + lhEst, lhEst != null && lhEst in 10..120)
            val lh = lhEst!!
            val ctrl = measureScroll(pre, pre2, lh, 4 * lh)
            Log.i(TAG, "scroll: negative control " + ctrl)
            assertTrue("scroll: capture UNSTABLE without gesture - outcome unprovable: " + ctrl,
                ctrl.status == "ACCEPT" && ctrl.shiftPx <= 2)
            Shot.save("scroll-b-pre")

            val xtCenter = guestToDevice(roiX + roiW / 2.0, roiY + roiH / 2.0)
            tap(instr, xtCenter.first.toFloat(), xtCenter.second.toFloat())
            Thread.sleep(600)
            twoFingerDrag(instr, xtCenter.first.toFloat(), xtCenter.second.toFloat(), 300f * wheelUpDySign)
            Thread.sleep(2_500)
            Shot.save("scroll-b-post")
            val (post, bmpPost) = shotRoi()
            val m = measureScroll(pre, post, lh, 12 * lh)
            Log.i(TAG, "scroll: outcome " + m + " (lh=" + lh + "px, ~" + (m.shiftPx / lh) + " lines)")
            bmpPre.recycle(); bmpPre2.recycle(); bmpPost.recycle()
            assertTrue("scroll: outcome correlation rejected honestly (no largest-peak acceptance): " + m,
                m.status == "ACCEPT")
            assertTrue("scroll: no scrollback movement - shift " + m.shiftPx + "px < 1 line (" + lh + "px)",
                m.shiftPx >= lh)
            assertTrue("scroll: implausible shift " + m.shiftPx + "px > 24 lines", m.shiftPx <= 24 * lh)
            Log.i(TAG, "SCROLL-OUTCOME-OK wheel delivered to legacy client (xev button4/5) " +
                "and xterm scrollback content moved ~" + (m.shiftPx / lh) + " lines " +
                "(CI emulator only - no smoothness/FPS/ARM claim)")
        } finally {
            // Fail-closed AND clean: a failed cell must not leak its
            // session; a cleanup failure must not mask the primary result.
            try { xevProc?.destroy() } catch (t: Throwable) { }
            try { xtermProc?.destroy() } catch (t: Throwable) { }
            try {
                val left = killTreeVerified(rootIdent, heldIdents + setOfNotNull(busIdentity))
                if (left.isEmpty()) Log.i(TAG, "scroll: finally cleanup no-op - all captured identities already reaped")
                else Log.e(TAG, "scroll: finally reaped leaked identities: " + left)
            } catch (t: Throwable) {
                Log.e(TAG, "scroll: finally cleanup failed - primary result preserved", t)
            }
            try { dumpLogTail("scroll-xev.log", xevLog, 16384) } catch (t: Throwable) { }
            try { dumpLogTail("scroll-xterm.log", xtermLog, 16384) } catch (t: Throwable) { }
            MobileModeSettings.write(ctx, defaultPrefs)
        }
    }

    /** Held guest process for test-owned X clients (xev control, the
     *  scrolltest xterm): its own proot root with --kill-on-exit so
     *  destroy() reaps the whole tree, PLINUX_SESSION tag for the tagged
     *  sweep, output to a device-side log file. The caller captures the
     *  root identity for the finally sweep. */
    private fun heldGuest(cmd: List<String>, log: File): Process {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val nd = ctx.applicationInfo.nativeLibraryDir
        val rootfs = DistroInstaller(ctx).rootfsDir(Distros.UBUNTU)
        val pb = ProcessBuilder(listOf(
            File(nd, "libproot.so").absolutePath, "--link2symlink", "--kill-on-exit", "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", XServer.socketDir(ctx).absolutePath + ":/tmp/.X11-unix",
            "-w", "/root") + cmd)
        pb.environment().putAll(mapOf(
            "PROOT_LOADER" to File(nd, "libproot_loader.so").absolutePath,
            "PROOT_LOADER_32" to File(nd, "libproot_loader32.so").absolutePath,
            "LD_LIBRARY_PATH" to nd,
            "HOME" to "/root",
            "DISPLAY" to ":0",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_TMP_DIR" to Proot.prootTmpDir(ctx).absolutePath,
            "TMPDIR" to "/tmp",
            "PLINUX_SESSION" to "desktop"))
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        return pb.start()
    }

    private class WinGeom(val id: String, val x: Int, val y: Int, val w: Int, val h: Int) {
        override fun toString(): String = id + " " + w + "x" + h + "+" + x + "+" + y
    }

    /** Named CLIENT window geometry from xwininfo -root -tree: the tree
     *  line is `id "name": ("Class" "class")  WxH+relX+relY  +absX+absY`;
     *  the ABSOLUTE pair is used. */
    private fun windowGeom(tree: String, title: String): WinGeom? {
        val re = Regex("(0x[0-9a-fA-F]+) \"[^\"]*" + Regex.escape(title) + "[^\"]*\": \\([^)]*\\)\\s+" +
            "(\\d+)x(\\d+)[+-](\\d+)[+-](\\d+)\\s+[+-](\\d+)[+-](\\d+)")
        val m = re.find(tree) ?: return null
        val g = m.groupValues
        return WinGeom(g[1], g[6].toInt(), g[7].toInt(), g[2].toInt(), g[3].toInt())
    }

    /** Mean luminance per pixel row of a Bitmap rect (columns subsampled
     *  every 3 px). */
    private fun rowBrightness(bmp: android.graphics.Bitmap, x: Int, y: Int, w: Int, h: Int, colStep: Int = 3): DoubleArray {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x, y, w, h)
        val out = DoubleArray(h)
        for (r in 0 until h) {
            var sum = 0.0; var n = 0
            var c = 0
            while (c < w) {
                val v = px[r * w + c]
                sum += 0.2126 * ((v shr 16) and 0xFF) + 0.7152 * ((v shr 8) and 0xFF) + 0.0722 * (v and 0xFF)
                n++; c += colStep
            }
            out[r] = sum / n
        }
        return out
    }

    /** Median gap between strong row-luminance edges = text line height,
     *  measured from the actual frame (never hardcoded). */
    private fun estLineHeight(d: DoubleArray): Int? {
        val mean = d.average()
        val sd = Math.sqrt(d.map { (it - mean) * (it - mean) }.average())
        if (sd <= 0) return null
        val edges = ArrayList<Int>()
        for (i in 0 until d.size - 1) if (Math.abs(d[i + 1] - d[i]) > 0.5 * sd) edges.add(i)
        if (edges.size < 4) return null
        val gaps = (1 until edges.size).map { edges[it] - edges[it - 1] }.filter { it in 10..84 }.sorted()
        return if (gaps.isEmpty()) null else gaps[gaps.size / 2]
    }

    private class ScrollMeasure(val status: String, val shiftPx: Int, val corr: Double, val margin: Double) {
        override fun toString(): String = "status=" + status + " shiftPx=" + shiftPx +
            " corr=" + "%.4f".format(corr) + " margin=" + "%.4f".format(margin)
    }

    /** Bounded-window Pearson shift: post = pre shifted DOWN by s px
     *  (scrollback content moves down in view). ACCEPT needs corr >= 0.95
     *  AND margin >= 0.03 over every candidate outside +-lh/2 of the
     *  winner; LOWCORR/AMBIGUOUS are honest failures, never a default
     *  acceptance of the largest peak. Locally validated 2026-11-01
     *  against zero/known 2/3/7-line/negative shifts, an unrelated frame,
     *  a cursor-blink frame and 2x noise: all recovered within 2px or
     *  rejected; periodic content correctly lands AMBIGUOUS. */
    private fun measureScroll(pre: DoubleArray, post: DoubleArray, lhPx: Int, maxShiftPx: Int): ScrollMeasure {
        val h = minOf(pre.size, post.size)
        val maxS = minOf(maxShiftPx, h / 2)
        fun corrAt(s: Int): Double {
            val n = h - s
            var ma = 0.0; var mb = 0.0
            for (i in 0 until n) { ma += pre[i]; mb += post[s + i] }
            ma /= n; mb /= n
            var sab = 0.0; var saa = 0.0; var sbb = 0.0
            for (i in 0 until n) {
                val da = pre[i] - ma; val db = post[s + i] - mb
                sab += da * db; saa += da * da; sbb += db * db
            }
            val dv = Math.sqrt(saa * sbb)
            return if (dv > 0) sab / dv else 0.0
        }
        var best = 0; var cb = Double.NEGATIVE_INFINITY
        for (s in 0..maxS) {
            val c = corrAt(s)
            if (c > cb) { cb = c; best = s }
        }
        var second = Double.NEGATIVE_INFINITY
        val lo = Math.max(0, best - lhPx / 2); val hi = Math.min(maxS, best + lhPx / 2)
        for (s in 0..maxS) {
            if (s in lo..hi) continue
            val c = corrAt(s)
            if (c > second) second = c
        }
        val margin = if (second == Double.NEGATIVE_INFINITY) cb else cb - second
        val status = when {
            cb < 0.95 -> "LOWCORR"
            margin < 0.03 -> "AMBIGUOUS"
            else -> "ACCEPT"
        }
        return ScrollMeasure(status, best, cb, margin)
    }

    // ---- MotionEvent helpers (multi-pointer sequences; same mechanism
    // as the GNOME harness: Instrumentation.sendPointerSync) ----

    private fun mProps(id: Int) = android.view.MotionEvent.PointerProperties().apply {
        this.id = id; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
    }

    private fun mCoords(x: Float, y: Float) = android.view.MotionEvent.PointerCoords().apply {
        this.x = x; this.y = y; pressure = 1f; size = 1f
    }

    private fun ev1(down: Long, action: Int, x: Float, y: Float) = android.view.MotionEvent.obtain(down,
        android.os.SystemClock.uptimeMillis(), action, 1,
        arrayOf(mProps(0)), arrayOf(mCoords(x, y)), 0, 0, 1f, 1f, 0, 0,
        android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)

    private fun ev2(down: Long, action: Int, actionIndex: Int,
            x0: Float, y0: Float, x1: Float, y1: Float): android.view.MotionEvent {
        val act = if (action == android.view.MotionEvent.ACTION_POINTER_DOWN ||
            action == android.view.MotionEvent.ACTION_POINTER_UP)
            action or (actionIndex shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        return android.view.MotionEvent.obtain(down,
            android.os.SystemClock.uptimeMillis(), act, 2,
            arrayOf(mProps(0), mProps(1)), arrayOf(mCoords(x0, y0), mCoords(x1, y1)), 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun sendEv(instr: android.app.Instrumentation, e: android.view.MotionEvent) {
        try { instr.sendPointerSync(e) } finally { e.recycle() }
    }

    private fun tap(instr: android.app.Instrumentation, x: Float, y: Float) {
        val down = android.os.SystemClock.uptimeMillis()
        sendEv(instr, ev1(down, android.view.MotionEvent.ACTION_DOWN, x, y))
        Thread.sleep(60)
        sendEv(instr, ev1(down, android.view.MotionEvent.ACTION_UP, x, y))
    }

    /** Two-finger vertical drag: DOWN, second finger +60px, 8 MOVE steps
     *  50ms apart, lifts. dy > 0 = fingers move DOWN the screen. */
    private fun twoFingerDrag(instr: android.app.Instrumentation, x: Float, y: Float, dy: Float) {
        val down = android.os.SystemClock.uptimeMillis()
        sendEv(instr, ev1(down, android.view.MotionEvent.ACTION_DOWN, x, y))
        Thread.sleep(80)
        sendEv(instr, ev2(down, android.view.MotionEvent.ACTION_POINTER_DOWN, 1, x, y, x + 60f, y))
        Thread.sleep(80)
        val steps = 8
        for (i in 1..steps) {
            sendEv(instr, ev2(down, android.view.MotionEvent.ACTION_MOVE, 0,
                x, y + dy * i / steps, x + 60f, y + dy * i / steps))
            Thread.sleep(50)
        }
        sendEv(instr, ev2(down, android.view.MotionEvent.ACTION_POINTER_UP, 1, x, y + dy, x + 60f, y + dy))
        Thread.sleep(40)
        sendEv(instr, ev1(down, android.view.MotionEvent.ACTION_UP, x, y + dy))
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
    private fun killTreeVerified(rootIdent: PIdent?, extraIdents: Set<PIdent?> = emptySet()): List<Int> {
        val idents = HashMap<Int, PIdent>()
        if (rootIdent != null) {
            // Root identity BEFORE the walk (owner review of 7c96b4a):
            // treeOf follows CURRENT ppid links, so a root pid that died
            // and got recycled would drag an unrelated tree into the kill
            // set. Dead or (starttime, uid)-mismatched root -> skip the
            // walk entirely, no signals from the root path at all.
            val cur = identityOf(rootIdent.pid)
            if (cur != null && cur.start == rootIdent.start && cur.uid == rootIdent.uid) {
                for (pid in treeOf(rootIdent.pid, scanProc())) identityOf(pid)?.let { idents[pid] = it }
            } else {
                Log.i(TAG, "killTreeVerified: root pid=" + rootIdent.pid + " dead or reused - walk skipped, no root-path signals")
            }
        }
        for (e in extraIdents) if (e != null) idents[e.pid] = e
        if (idents.isEmpty()) return emptyList()
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
