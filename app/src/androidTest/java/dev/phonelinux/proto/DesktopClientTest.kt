package dev.phonelinux.proto

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * The GUI milestone: a REAL guest X client (xclock installed via apk inside
 * the proot guest) draws into the embedded X server through the bound
 * socket, displayed by the vendored lorie activity. Evidence: socket probe,
 * xclock stays alive, and 07-desktop-client.png captured via shell screencap
 * (inspected as a CI artifact).
 */
@RunWith(AndroidJUnit4::class)
// NAME_ASCENDING enforces desktop1_ before desktop2_: the restart test leaves
// the lorie activity + X service running with the DEFAULT (trackpad) touch
// mode, and the interaction suite's direct-touch calibration then reads a
// stale pref (runs 8302b1e: tapA==tapB; 772fadb: drag no-op). The restart
// test itself is tap-free (process-verified), so it is safe to run after.
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DesktopClientTest {

    @Test
    fun desktop1_fullInteractionSuite(): Unit = runBlocking {
        val appCtx = ApplicationProvider.getApplicationContext<Context>()
        // Establish own state FIRST: the lorie activity must never exist in
        // this process before touchMode=3 is on disk. (Root-cause question
        // from runs 8302b1e/772fadb: a pref written AFTER the activity is
        // running may not apply live - the probe at the end of this method
        // characterizes that instead of asserting on a guess.)
        appCtx.getSharedPreferences(appCtx.packageName + "_preferences", Context.MODE_PRIVATE)
            .edit().putString("touchMode", "3").commit()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val installer = DistroInstaller(appCtx)
        val distro = Distros.ALPINE
        if (!installer.isInstalled(distro)) {
            assertTrue("distro install failed", installer.install(distro) { })
        }
        val rootfs = installer.rootfsDir(distro)

        XServer.start(appCtx) // provisions xkb data, spawns server

        val sock = File(XServer.socketDir(appCtx), "X0").absolutePath
        val deadline = System.currentTimeMillis() + 60_000
        var up = false
        while (System.currentTimeMillis() < deadline) {
            val ls = try { device.executeShellCommand("run-as dev.phonelinux.proto ls $sock 2>&1") } catch (t: Throwable) { "" }
            if (ls.trim() == sock) { up = true; break }
            Thread.sleep(1_000)
        }
        val logTail = try { device.executeShellCommand("run-as dev.phonelinux.proto tail -c 2000 files/xserver.log 2>&1") } catch (t: Throwable) { "(log unreadable)" }
        assertTrue("X socket never appeared. Server log tail:\n$logTail", up)

        if (!GuestDesktop.hasXclock(rootfs)) {
            var add = GuestDesktop.provisionXclock(appCtx, rootfs)
            if (add.exitCode != 0) {
                Thread.sleep(5_000)
                add = GuestDesktop.provisionXclock(appCtx, rootfs) // transient fetch errors
            }
            if (add.exitCode != 0) {
                // Trusted-path diagnosis (no --allow-untrusted ever): prove the
                // Alpine signing keys survived extraction host-side, then look
                // at the guest view of keys/repos and a plain apk update.
                val keysDir = java.io.File(rootfs, "etc/apk/keys")
                val hostKeys = keysDir.listFiles()?.joinToString(", ") { "${it.name}:${it.length()}B" } ?: "(keys dir MISSING)"
                val guest = GuestDesktop.guestExec(appCtx, rootfs,
                    "ls -l /etc/apk/keys; echo ---; cat /etc/apk/repositories; echo ---; cat /etc/apk/arch; echo ---; apk --version")
                val upd = GuestDesktop.guestExec(appCtx, rootfs, "apk update 2>&1")
                throw AssertionError("apk add xclock failed (exit ${add.exitCode})" +
                    "\nadd out: ${(add.stdout + add.stderr).takeLast(900)}" +
                    "\nhost keys: $hostKeys" +
                    "\nguest view: ${(guest.stdout + guest.stderr).takeLast(800)}" +
                    "\napk update exit=${upd.exitCode}: ${(upd.stdout + upd.stderr).takeLast(800)}")
            }
        }
        assertTrue("xclock missing after provisioning", GuestDesktop.hasXclock(rootfs))

        val client = GuestDesktop.launchXclock(appCtx, rootfs)
        appCtx.startActivity(
            Intent(appCtx, com.termux.x11.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Thread.sleep(12_000)
        val guiLog = try { device.executeShellCommand("run-as dev.phonelinux.proto tail -c 1000 files/guest-gui.log 2>&1") } catch (t: Throwable) { "" }
        assertTrue("xclock exited early - not drawing. Guest log:\n$guiLog", client.isAlive)

        Shot.dismissSystemDialogs(device)
        Shot.save("07-desktop-client")

        client.destroy()

        // Desktop shell phase: real wm + panel + file manager, the
        // computer-like desktop the user asked for.
        if (!GuestDesktop.hasDesktopShell(rootfs)) {
            var add = GuestDesktop.provisionDesktopShell(appCtx, rootfs)
            if (add.exitCode != 0) {
                Thread.sleep(5_000)
                add = GuestDesktop.provisionDesktopShell(appCtx, rootfs) // one retry: transient fetch errors
            }
            val arch = GuestDesktop.guestShell(appCtx, rootfs, "apk --print-arch")
            assertTrue("apk add desktop shell failed (exit ${add.exitCode}), guest apk arch=$arch\n${(add.stdout + add.stderr).takeLast(2400)}", add.exitCode == 0)
        }
        assertTrue("desktop shell missing after provisioning", GuestDesktop.hasDesktopShell(rootfs))

        val session = GuestDesktop.launchDesktopSession(appCtx, rootfs)
        try {
        Thread.sleep(25_000)
        val wmAlive = GuestDesktop.guestOk(appCtx, rootfs,
            "pgrep -x openbox || pgrep -x jwm || pgrep -x icewm || pgrep -x fluxbox")
        val panelAlive = GuestDesktop.guestOk(appCtx, rootfs, "pgrep tint2")
        val fmAlive = GuestDesktop.guestOk(appCtx, rootfs, "pgrep pcmanfm")
        // A real terminal window on the desktop. Root cause of the earlier
        // deaths: getpwuid(getuid()) failed for the app uid (st.c execsh ->
        // die("who are you?")); the session script now writes the passwd
        // entry first, then ladders xterm -> st -> fallbacks.
        var termAlive = false
        // 120s covers terminal spawn under proot on a loaded emulator.
        val xtDeadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < xtDeadline) {
            if (GuestDesktop.guestOk(appCtx, rootfs, "pgrep -x xterm || pgrep -x st")) { termAlive = true; break }
            Thread.sleep(3_000)
        }
        val deskLogRaw = try { device.executeShellCommand("run-as dev.phonelinux.proto tail -c 4000 files/guest-desktop.log 2>&1") } catch (t: Throwable) { "" }
        val deskLog = deskLogRaw.map { c -> if (c.code in 32..126 || c == '\n') c else '?' }.joinToString("")
        if (!wmAlive) {
            // Run openbox standalone to capture its actual startup error.
            throw AssertionError("no window manager survived." +
                "\ndesktop log:\n$deskLog")
        }
        val chosenWm = Regex("chosen-wm=(\\w+)").find(deskLog)?.groupValues?.get(1) ?: "(none in log)"
        android.util.Log.i("PHONEDIAG", "desktop log tail: " + deskLog.takeLast(1200).replace("\n", "|"))
        android.util.Log.i("PHONEDIAG", "chosen window manager: " + chosenWm)
        // tint2 only runs when the chain fell back to openbox; other WMs
        // (jwm/icewm/fluxbox) draw their own tray, already covered by wmAlive.
        assertTrue("tint2 not running (wm=" + chosenWm + "). Desktop log:\n$deskLog",
            chosenWm != "openbox" || panelAlive)
        assertTrue("pcmanfm not running. Desktop log:\n$deskLog", fmAlive)
        assertTrue("no terminal window (xterm/st) survived. Desktop log:\n$deskLog", termAlive)
        assertTrue("desktop session exited early. Desktop log:\n$deskLog", session.isAlive)

        // Geometry evidence for the panel/icons visibility question: the
        // full X window tree and screen size, timed with the 08 shot.
        val winTree = GuestDesktop.guestShell(appCtx, rootfs,
            "export DISPLAY=:0; xwininfo -root -tree 2>&1 | head -c 4000; echo; xdpyinfo 2>/dev/null | grep dimensions",
            listOf(XServer.socketDir(appCtx).absolutePath to "/tmp/.X11-unix"))
        android.util.Log.i("PHONEDIAG", "x window tree: " + winTree.replace("\n", "|"))
        // Depth/visual per top-level window: tests the hypothesis that
        // 32-bit-visual windows (tint2, gtk apps) render black under lorie.
        val winDepths = GuestDesktop.guestShell(appCtx, rootfs,
            "export DISPLAY=:0; for w in ${'$'}(xwininfo -root -children | awk '/^ *0x/ {print ${'$'}1}'); do echo == ${'$'}w; xwininfo -id ${'$'}w -stats 2>/dev/null | grep -E 'Map State|Depth:|Visual Class:'; done",
            listOf(XServer.socketDir(appCtx).absolutePath to "/tmp/.X11-unix"))
        android.util.Log.i("PHONEDIAG", "x window depths: " + winDepths.replace("\n", "|"))
        Shot.dismissSystemDialogs(device)
        Shot.save("08-desktop-shell-" + chosenWm)

        // ---- Interaction phase: real taps/drags through the Xlorie touch
        // pipeline, geometry-verified via xwininfo and pixel-inspected from
        // the screenshots. jwm-gated: targets are jwm-specific (tray
        // coordinates, title-bar layout); the openbox fallback skips.
        if (chosenWm != "jwm") {
            throw AssertionError("interaction coverage requires jwm, got chosen-wm=" + chosenWm +
                " - a fallback/skip must NOT count as a pass")
        } else {
        // Direct-touch mode (touchMode=3) maps injected device coordinates
        // linearly onto X coordinates; default trackpad mode is relative and
        // untestable. The affine map (scale + offset) is not known a priori
        // (run a6abbd6: a tap aimed with a hand-estimated offset missed the
        // tray entirely), so CALIBRATE: two taps at points inside the st
        // client area (harmless for any plausible map), reading the X
        // pointer position after each via xdotool. Identical reads would
        // mean the pref did not take effect (trackpad clicks land wherever
        // the pointer already was).
        // touchMode=3 was committed at method top, before any activity start.
        Thread.sleep(1_500)

        val xBinds = listOf(XServer.socketDir(appCtx).absolutePath to "/tmp/.X11-unix")
        fun xwin(query: String): String = GuestDesktop.guestShell(appCtx, rootfs,
            "export DISPLAY=:0; " + query, xBinds)
        fun topCount(): Int = xwin("xwininfo -root -children | grep -c '^ *0x'")
            .trim().toIntOrNull() ?: -1
        fun pointerPos(): Pair<Int, Int>? {
            val out = xwin("xdotool getmouselocation --shell 2>/dev/null")
            val x = Regex("^X=(\\d+)", RegexOption.MULTILINE).find(out)?.groupValues?.get(1)?.toIntOrNull()
            val y = Regex("^Y=(\\d+)", RegexOption.MULTILINE).find(out)?.groupValues?.get(1)?.toIntOrNull()
            return if (x != null && y != null) Pair(x, y) else null
        }

        android.util.Log.i("PHONEDIAG", "pointer at start: " + pointerPos())
        // Tap-to-warp calibration with bounded retry: one dropped tap
        // (transient system window, input-device re-enumeration) must not
        // fail the suite, but a mode that NEVER moves the pointer must.
        // Runs 8302b1e/772fadb/425ab98: tap A warped, tap B silently did
        // not (no cursor update in logcat); the retry characterizes which.
        val tapPoints = listOf(Pair(540, 800), Pair(400, 750), Pair(600, 850), Pair(360, 700), Pair(520, 760))
        val reads = mutableListOf<Pair<Pair<Int, Int>, Pair<Int, Int>?>>()
        var calib: Pair<Pair<Pair<Int, Int>, Pair<Int, Int>>, Pair<Pair<Int, Int>, Pair<Int, Int>>>? = null
        for (tp in tapPoints) {
            Shot.dismissSystemDialogs(device)
            device.click(tp.first, tp.second)
            Thread.sleep(1_000)
            val r = pointerPos()
            reads.add(tp to r)
            android.util.Log.i("PHONEDIAG", "calibration tap(" + tp.first + "," + tp.second + ")->" + r)
            val prev = reads.firstOrNull { it.second != null && r != null &&
                Math.abs(it.second!!.first - r.first) > 20 && Math.abs(it.second!!.second - r.second) > 10 }
            if (prev != null && r != null) {
                calib = (prev.first to prev.second!!) to (tp to r)
                break
            }
        }
        if (calib == null) {
            val focus = try { device.executeShellCommand("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'") } catch (t: Throwable) { "(dumpsys failed)" }
            throw AssertionError("direct-touch mapping never observed; taps=" + reads + " focusedWindow=" + focus)
        }
        if (reads.size > 2) {
            // Dropped taps are a REAL usability signal, not noise to hide:
            // record how many taps the run lost before the map locked.
            android.util.Log.w("PHONEDIAG", "DROPPED-TAPS=" + (reads.size - 2) +
                " of " + reads.size + " calibration taps - repeated touch loss would be a product input defect; reads=" + reads)
        }
        val tap1 = calib.first; val tap2 = calib.second
        val a = tap1.second; val b = tap2.second
        val sX = (a.first - b.first) / (tap1.first.first - tap2.first.first).toDouble()
        val sY = (a.second - b.second) / (tap1.first.second - tap2.first.second).toDouble()
        val oX = a.first - sX * tap1.first.first
        val oY = a.second - sY * tap1.first.second
        android.util.Log.i("PHONEDIAG", "calibrated map sX=" + sX + " oX=" + oX + " sY=" + sY + " oY=" + oY)

        // Anchor all st measurements to the ORIGINAL terminal's window id:
        // after the menu-launch test two "script" clients exist and
        // xwininfo -name script picks an arbitrary one (run 9633cdc: the
        // resize/offscreen asserts measured the menu-launched st).
        val stWid = xwin("xdotool search --name script | head -n 1").trim()
        android.util.Log.i("PHONEDIAG", "anchored st window id: " + stWid)
        // xdotool prints DECIMAL ids (8388613 = 0x800005); xwininfo -id
        // accepts either form.
        assertTrue("original st window id not found: '" + stWid + "'",
            stWid.matches(Regex("(0x[0-9a-fA-F]+|[0-9]+)")))
        fun devFor(x: Double, y: Double) = Pair(((x - oX) / sX).toInt(), ((y - oY) / sY).toInt())

        // A modal system ANR dialog blocks input to the X surface (run
        // 40811548463 st-maximize tap, run 40814163677 JWM menu tap: both
        // eaten by a Pixel Launcher ANR dialog, pixel-verified). Bounded
        // environment recovery before critical X gestures: dismiss only
        // allowlisted system ANRs via the shared helper, log the count,
        // assert the path is clear; anything else stays up and fails.
        var anrReposts = 0
        var anrSeen = 0
        var anrRecoveryUsed = false
        fun anrDialogUp(): Boolean =
            device.findObject(androidx.test.uiautomator.By.textContains("isn't responding")) != null ||
                device.findObject(androidx.test.uiautomator.By.textContains("Not Responding")) != null
        fun isDisposableEmulator(): Boolean {
            val qemu = device.executeShellCommand("getprop ro.kernel.qemu").trim()
            val hw = device.executeShellCommand("getprop ro.hardware").trim()
            return qemu == "1" || hw.contains("goldfish") || hw.contains("ranchu")
        }
        // Bounded environment recovery for the re-posting launcher ANR
        // storm (run 40937099472: fresh dialog every ~6s for minutes,
        // defeating point-in-time guards). Disposable emulator ONLY, known
        // nexuslauncher ANR ONLY, ONCE per test, loudly logged, counts
        // preserved. Recovery is VERIFIED: the launcher may not self-
        // restart, so if pidof stays empty the known component is started
        // explicitly (and logged); a 10s quiet window must stay clean.
        // Persistent storm after the single recovery fails the test as
        // untreated - no loop.
        fun anrEnvRecover(stage: String) {
            assertTrue("re-posted ANR on a non-emulator; environment recovery refused (untreated)",
                isDisposableEmulator())
            val attr = device.executeShellCommand(
                "dumpsys window windows | grep -m1 'Application Not Responding' || true").trim()
            assertTrue("re-posted ANR is not the known nexuslauncher case, refusing recovery: " + attr,
                attr.contains("com.google.android.apps.nexuslauncher"))
            anrRecoveryUsed = true
            android.util.Log.i("PHONEDIAG", "ENVIRONMENT-RECOVERY-BEGIN[" + stage +
                "]: force-stop com.google.android.apps.nexuslauncher (disposable emulator, anrSeen=" + anrSeen + ")")
            device.executeShellCommand("am force-stop com.google.android.apps.nexuslauncher")
            var pid = ""
            var dl = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < dl) {
                pid = device.executeShellCommand("pidof com.google.android.apps.nexuslauncher").trim()
                if (pid.isNotEmpty()) break
                Thread.sleep(1_000)
            }
            if (pid.isEmpty()) {
                android.util.Log.i("PHONEDIAG", "ENVIRONMENT-RECOVERY[" + stage +
                    "]: launcher did not self-restart; explicitly starting known component")
                device.executeShellCommand(
                    "am start -n com.google.android.apps.nexuslauncher/.NexusLauncherActivity")
                dl = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < dl) {
                    pid = device.executeShellCommand("pidof com.google.android.apps.nexuslauncher").trim()
                    if (pid.isNotEmpty()) break
                    Thread.sleep(1_000)
                }
            }
            var quiet = true
            val qEnd = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < qEnd) {
                if (anrDialogUp()) { quiet = false; break }
                Thread.sleep(1_000)
            }
            android.util.Log.i("PHONEDIAG", "ENVIRONMENT-RECOVERY-END[" + stage +
                "]: launcherPid=" + pid + " quietWindowClean=" + quiet)
            assertTrue("environment recovery failed (untreated ANR storm): launcherPid=" + pid +
                " quietWindowClean=" + quiet, pid.isNotEmpty() && quiet)
        }
        fun anrRecheck(stage: String) {
            if (anrDialogUp()) {
                anrReposts++
                anrSeen++
                if (anrSeen >= 2 && !anrRecoveryUsed) {
                    anrEnvRecover(stage)
                } else {
                    android.util.Log.i("PHONEDIAG", "anr-recheck[" + stage + "]: re-posted ANR dialog dismissed (total re-posts=" + anrReposts + ")")
                    Shot.dismissSystemDialogs(device)
                }
            }
        }

        fun anrGuard(stage: String) {
            var dismissed = 0
            while (dismissed < 3) {
                val t = device.findObject(androidx.test.uiautomator.By.textContains("isn't responding"))
                    ?: device.findObject(androidx.test.uiautomator.By.textContains("Not Responding"))
                    ?: break
                Shot.dismissSystemDialogs(device)
                dismissed++
            }
            anrSeen += dismissed
            android.util.Log.i("PHONEDIAG", "anr-guard[" + stage + "]: allowlisted dialogs dismissed=" + dismissed)
            // A dismissal is not proof the storm is over: the dialog
            // re-posts within seconds while the launcher stays hung
            // (20:18:30 dismissed -> 20:18:33 re-created, run 40945787051).
            // Watch a bounded window after any dismissal; a re-post is
            // treated as the storm case and goes to recovery, not another
            // silent dismiss cycle.
            if (dismissed > 0 && !anrRecoveryUsed) {
                val wEnd = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < wEnd) {
                    Thread.sleep(1_000)
                    if (anrDialogUp()) {
                        anrSeen++
                        android.util.Log.i("PHONEDIAG", "anr-guard[" + stage + "]: ANR re-posted inside post-dismiss window (anrSeen=" + anrSeen + ")")
                        anrEnvRecover(stage)
                        break
                    }
                }
            }
            assertTrue("system ANR dialog still up before " + stage,
                device.findObject(androidx.test.uiautomator.By.textContains("isn't responding")) == null &&
                device.findObject(androidx.test.uiautomator.By.textContains("Not Responding")) == null)
        }
        // Bounded recheck for long steps: the launcher ANR dialog can
        // RE-POST every ~6s while the process stays hung (run 40937099472:
        // fresh dialog windows 19:21:08 -> 19:24:48+), so a guard before a
        // gesture leaves a re-post window during the settle. Each dismissal
        // is counted and logged; repeats stay visible, never hidden.

        // 1. Tap the JWM menu button (tray = 1080x34+0+1978, button at the
        // left end ~X(20,1995)). jwm menus are override-redirect popups: a
        // new top-level window appears while the menu is open.
        anrGuard("jwm-menu")
        val menuBtn = devFor(20.0, 1995.0)
        val menuBefore = topCount()
        device.click(menuBtn.first, menuBtn.second)
        Thread.sleep(2_000)
        Shot.save("09-jwm-menu-tapped")
        val menuAfter = topCount()
        android.util.Log.i("PHONEDIAG", "jwm-menu tap dev=" + menuBtn + " windows before=" + menuBefore + " after=" + menuAfter)
        assertTrue("JWM menu did not open after tapping the menu button (dev=" + menuBtn +
            " windows before=" + menuBefore + " after=" + menuAfter + ")", menuAfter > menuBefore)
        // Dismiss by focusing the st client area (NOT empty desktop: jwm
        // binds root-window clicks to the same menu).
        val stBody = devFor(500.0, 700.0)
        device.click(stBody.first, stBody.second)
        Thread.sleep(1_000)

        // 2. Drag the st window by its jwm title bar (frame 1012x605+40+420,
        // title bar ~27px at the frame top -> X(546,433)) and verify the
        // geometry moved.
        fun stPos(): Pair<Int, Int>? {
            val out = xwin("xwininfo -id " + stWid + " -stats | grep 'Absolute upper-left' | awk '{print ${'$'}NF}'")
            val n = out.lines().mapNotNull { it.trim().toIntOrNull() }
            return if (n.size >= 2) Pair(n[0], n[1]) else null
        }
        val dragFrom = devFor(546.0, 433.0)
        // Modest drag kept fully onscreen (the +200/+200 variant pushed the
        // window controls off the right edge - see the dedicated offscreen
        // recovery test below).
        // Bounded retry: the emulator's Pixel-Launcher ANR dialog eats the
        // swipe (pixel-proven in shots 10 of runs 8f18370/eaaf47f/1a81e3d) -
        // dismiss and re-attempt rather than failing on the storm.
        var stBefore = stPos()
        var stAfter = stPos()
        var moved = false
        for (attempt in 1..3) {
            Shot.dismissSystemDialogs(device)
            Thread.sleep(500)
            stBefore = stPos()
            device.swipe(dragFrom.first, dragFrom.second, dragFrom.first - 30, dragFrom.second - 150, 40)
            Thread.sleep(2_000)
            stAfter = stPos()
            if (stBefore != null && stAfter != null &&
                (Math.abs(stAfter.first - stBefore.first) > 50 || Math.abs(stAfter.second - stBefore.second) > 50)) {
                moved = true; break
            }
            android.util.Log.i("PHONEDIAG", "st drag attempt " + attempt + " no move (before=" + stBefore + " after=" + stAfter + ")")
        }
        Shot.save("10-st-dragged")
        android.util.Log.i("PHONEDIAG", "st drag from dev=" + dragFrom + " before=" + stBefore + " after=" + stAfter + " moved=" + moved)
        assertTrue("st window did not move on title-bar drag after 3 attempts (before=" + stBefore + " after=" + stAfter + ")", moved)

        // 3. Launch from the JWM menu: open the root menu, identify the
        // popup by DIFFING top-level window ids (the JWM 1x1 support
        // window sorts last in the tree - position heuristics picked it
        // instead of the popup in run ca72793's predecessor), tap its
        // first item ("Terminal", pixel-verified in the 09 shot),
        // expect a second st client to map.
        fun topIds(): List<String> = xwin("xwininfo -root -children").lines()
            .filter { it.trimStart().startsWith("0x") }
        val stCountBefore = xwin("xwininfo -root -tree | grep -c st-256color").trim().toIntOrNull() ?: -1
        val beforeIds = topIds().map { it.trimStart().substringBefore(' ') }.toSet()
        // Same ANR-storm posture as the drag: the Pixel Launcher dialog ate
        // this tap in run 06654bc (shot 09/10 show the dialog at tap time).
        var newLines = listOf<String>()
        for (attempt in 1..3) {
            Shot.dismissSystemDialogs(device)
            Thread.sleep(500)
            device.click(menuBtn.first, menuBtn.second)
            Thread.sleep(2_000)
            newLines = topIds().filter { it.trimStart().substringBefore(' ') !in beforeIds }
            if (newLines.size == 1) break
            android.util.Log.i("PHONEDIAG", "menu second-tap attempt " + attempt + " new top-levels: " + newLines)
        }
        android.util.Log.i("PHONEDIAG", "menu new top-levels: " + newLines)
        assertTrue("JWM menu popup did not appear on second menu-button tap after 3 attempts (new top-levels=" + newLines + ")",
            newLines.size == 1)
        val gm = Regex("(\\d+)x(\\d+)\\+(-?\\d+)\\+(-?\\d+)").find(newLines[0])
        assertTrue("menu popup geometry unparsable: " + newLines[0], gm != null)
        val g0 = gm!!.groupValues
        val itemDev = devFor(g0[3].toInt() + g0[1].toInt() / 2.0, g0[4].toInt() + 14.0)
        var stCountAfter = -1
        for (attempt in 1..3) {
            Shot.dismissSystemDialogs(device)
            Thread.sleep(500)
            device.click(itemDev.first, itemDev.second)
            Thread.sleep(4_000)
            stCountAfter = xwin("xwininfo -root -tree | grep -c st-256color").trim().toIntOrNull() ?: -1
            if (stCountAfter > stCountBefore) break
            android.util.Log.i("PHONEDIAG", "menu-launch attempt " + attempt + ": st count before=" + stCountBefore + " after=" + stCountAfter)
            // menu closes on the failed tap; reopen for the retry
            Shot.dismissSystemDialogs(device)
            device.click(menuBtn.first, menuBtn.second)
            Thread.sleep(2_000)
        }
        Shot.save("11-menu-launched-terminal")
        android.util.Log.i("PHONEDIAG", "menu-launch: st count before=" + stCountBefore + " after=" + stCountAfter + " itemTap dev=" + itemDev)
        assertTrue("menu Terminal item did not launch a new st after 3 attempts (count before=" + stCountBefore + " after=" + stCountAfter + ")",
            stCountAfter > stCountBefore)

        // 4. Resize via touch. The jwm frame rect and title buttons are
        // MEASURED (parent-window query), never assumed.
        fun stGeom(): IntArray? {
            val out = xwin("xwininfo -id " + stWid + " -stats | grep -E 'Absolute upper-left|Width:|Height:' | awk '{print ${'$'}NF}'")
            val n = out.lines().mapNotNull { it.trim().toIntOrNull() }
            return if (n.size >= 4) intArrayOf(n[0], n[1], n[2], n[3]) else null
        }
        // Frame offsets measured from the window tree: border 4px,
        // title 27px -> frame = client + (-4, -27, +8, +31). Title
        // buttons right-aligned: close at frame-right -13, maximize -36.
        // 4a. Maximize: tap the title-bar maximize button and expect a
        // big geometry change.
        val gHome0 = stGeom()
        android.util.Log.i("PHONEDIAG", "resize: client=" + gHome0?.toList() +
            " parentLine=" + xwin("xwininfo -id " + stWid + " | grep 'Parent window id'").trim())
        assertTrue("st geometry unreadable for resize tests", gHome0 != null)
        val gh = gHome0!!
        anrGuard("st-maximize")
        val maxBtn0 = devFor(gh[0] + gh[2] + 4.0 - 36.0, gh[1] - 14.0)
        device.click(maxBtn0.first, maxBtn0.second)
        Thread.sleep(2_000)
        Shot.save("11b-st-maximized")
        val gMax = stGeom()
        android.util.Log.i("PHONEDIAG", "after maximize tap: " + gMax?.toList() + " btn dev=" + maxBtn0)
        assertTrue("st did not maximize on title-button tap (before=" + gh.toList() + " after=" + gMax?.toList() + ") - possible mis-tap onto close if window is gone",
            // Width only grows to screen-minus-borders (+60 from the
            // default 1004), height is the big axis (+1368 observed).
            gMax != null && gMax[2] > gh[2] + 40 && gMax[3] > gh[3] + 100)
        // 4b. Restore: tap the same button again, expect ~original size.
        val gm2 = gMax!!
        anrGuard("st-restore")
        val maxBtn1 = devFor(gm2[0] + gm2[2] + 4.0 - 36.0, gm2[1] - 14.0)
        device.click(maxBtn1.first, maxBtn1.second)
        Thread.sleep(2_000)
        val gRest = stGeom()
        android.util.Log.i("PHONEDIAG", "after restore tap: " + gRest?.toList())
        assertTrue("st did not restore on second maximize-button tap (after=" + gRest?.toList() + ")",
            gRest != null && Math.abs(gRest[2] - gh[2]) < 60 && Math.abs(gRest[3] - gh[3]) < 60)
        val gr2 = gRest!!
        // 4c. Free resize: drag the measured frame corner with drag()
        // (long-press semantics; swipe() produced a no-op in run
        // bd799a0 despite a verified on-border grab point).
        anrGuard("st-resize")
        val corner = devFor(gr2[0] + gr2[2] + 2.0, gr2[1] + gr2[3] + 2.0)
        device.drag(corner.first, corner.second, corner.first - 120, corner.second - 100, 40)
        Thread.sleep(2_000)
        val gFree = stGeom()
        android.util.Log.i("PHONEDIAG", "free resize: corner dev=" + corner + " before=" + gr2.toList() + " after=" + gFree?.toList())
        assertTrue("st did not resize on corner drag (before=" + gr2.toList() + " after=" + gFree?.toList() + ")",
            gFree != null &&
                (Math.abs(gFree[2] - gr2[2]) > 40 || Math.abs(gFree[3] - gr2[3]) > 40))

        // 5. Offscreen recovery: drag st right until its controls leave
        // the screen, then drag it back by the still-visible title strip
        // and verify the right edge (controls) is back onscreen.
        val gHome = stGeom()!!
        anrGuard("st-offscreen")
        // Single hold-drag only - no retries (qualification rule). Both
        // swipe and drag have produced zero-delta outcomes on this step
        // (runs 40824568558 swipe, 40827970133 drag), both inside Pixel
        // Launcher ANR storm windows; correlation, not proven causation.
        val tb = devFor(gHome[0] + 400.0, gHome[1] - 13.0)
        device.drag(tb.first, tb.second, tb.first + 600, tb.second, 40)
        Thread.sleep(2_000)
        val gOff = stGeom()
        android.util.Log.i("PHONEDIAG", "offscreen drag: before=" + gHome.toList() + " off=" + gOff?.toList())
        assertTrue("st did not move offscreen (before=" + gHome.toList() + " off=" + gOff?.toList() + ")",
            gOff != null && gOff[0] + gOff[2] + 8 > 1080)
        // Return gesture: ONE attempt only (qualification rule) - if a
        // repeat would be needed, qualification fails; no cleanup can turn
        // a failed return green.
        val go = gOff!!
        // Grab the ACTUAL visible titlebar segment, never a computed point
        // past the screen edge (1080-wide display).
        val grabGx = minOf(go[0] + go[2] - 100, minOf(go[0] + go[2], 1080) - 40)
        assertTrue("st titlebar not visibly grabbable (off=" + go.toList() + ")", grabGx > go[0] + 20)
        val tb2 = devFor(grabGx.toDouble(), go[1] - 13.0)
        anrGuard("st-recovery")
        device.drag(tb2.first, tb2.second, tb2.first + (20 - go[0]), tb2.second, 40)
        Thread.sleep(1_000)
        anrRecheck("st-recovery-settle")
        Thread.sleep(1_000)
        val gBack = stGeom()
        Shot.save("12-st-recovered")
        android.util.Log.i("PHONEDIAG", "recovery drag: off=" + go.toList() + " back=" + gBack?.toList())
        assertTrue("st controls not recovered onscreen in one attempt (off=" + go.toList() + " back=" + gBack?.toList() + ")",
            gBack != null && gBack[0] + gBack[2] + 8 <= 1080)
        android.util.Log.i("PHONEDIAG", "st-recovery outcome: cumulative ANR re-posts dismissed in desktop1=" + anrReposts +
            " anrSeen=" + anrSeen + " environmentRecoveryUsed=" + anrRecoveryUsed)

        // 6. White-stripe experiment: the band inside st exists only
        // after the calibration taps (absent pre-interaction, run
        // 29cb928 shot 08) and tracks the window -> st content, most
        // likely a text selection from the tap press/release pairs.
        // xrefresh clears damage artifacts; a single click in st clears
        // a selection. Shots 13/14 discriminate.
        xwin("xrefresh")
        Thread.sleep(1_000)
        Shot.save("13-stripe-after-xrefresh")
        val gEnd = stGeom()!!
        val center = devFor(gEnd[0] + gEnd[2] / 2.0, gEnd[1] + gEnd[3] / 2.0)
        device.click(center.first, center.second)
        Thread.sleep(1_000)
        Shot.save("14-stripe-after-click")

        // 7. Keyboard input INTO the X terminal: focus st with a tap in
        // its client area, inject a shell command through the Android
        // input pipeline, verify an X side effect (a second xclock maps).
        // The Compose-terminal UiFlow does not cover X input at all.
        // LAST: the IME this opens letterboxes the X viewport, which
        // invalidates the calibrated affine map for any later tap.
        val clocksBefore = xwin("xwininfo -root -tree | grep -c XClock").trim().toIntOrNull() ?: -1
        device.click(stBody.first, stBody.second)
        Thread.sleep(800)
        android.util.Log.i("PHONEDIAG", "st focus window: " + xwin("xdotool getactivewindow getwindowname"))
        device.executeShellCommand("input text xclock%s&")
        Thread.sleep(500)
        device.pressEnter()
        Thread.sleep(3_000)
        Shot.save("15-st-typed-xclock")
        val clocksAfter = xwin("xwininfo -root -tree | grep -c XClock").trim().toIntOrNull() ?: -1
        android.util.Log.i("PHONEDIAG", "st typed xclock: XClock count before=" + clocksBefore + " after=" + clocksAfter)
        assertTrue("typed command did not run in the X terminal (XClock count before=" + clocksBefore + " after=" + clocksAfter + ")",
            clocksBefore >= 0 && clocksAfter > clocksBefore)

        } // jwm-only interaction phase

        // Live-pref probe (parent directive: qualify, don't speculate): does
        // a touchMode change apply to a RUNNING lorie session, or only at
        // activity start? Probe = switch pref, tap a fixed point, read the X
        // pointer. Trackpad mode = pointer stays put; direct = pointer moves
        // to the tap. IME letterbox from the typing phase does not affect
        // the distinction. No assertion: this is behavior characterization.
        run {
            fun probe(mode: String): String {
                appCtx.getSharedPreferences(appCtx.packageName + "_preferences", Context.MODE_PRIVATE)
                    .edit().putString("touchMode", mode).commit()
                Thread.sleep(1_500)
                device.click(400, 600)
                Thread.sleep(800)
                return GuestDesktop.guestShell(appCtx, rootfs,
                    "export DISPLAY=:0; xdotool getmouselocation --shell 2>/dev/null",
                    listOf(XServer.socketDir(appCtx).absolutePath to "/tmp/.X11-unix"))
                    .replace("\n", " ")
            }
            val direct = probe("3")
            val track = probe("1")
            val direct2 = probe("3")
            android.util.Log.i("PHONEDIAG", "live pref probe: direct=[" + direct + "] trackpad=[" + track + "] directAgain=[" + direct2 + "]")
        }

        } finally {
            // Leave a lighter system for later tests even when an assertion
            // above throws (a live desktop session feeds launcher ANR storms).
            session.destroy()
            try { GuestDesktop.killDesktopClients(appCtx, rootfs) } catch (t: Throwable) { }
        }
    }

    // Restart persistence, the user-visible "close and come back" path, as
    // its OWN test: the giant desktop test fills its result payload near the
    // harness gRPC cap (run cd5dc6d: 4.28MB > 4MB overflow on result
    // reporting with every test green), so volume-heavy phases get their
    // own test boundary and their own result message. Self-contained: does
    // its own install/X/shell setup so JUnit method order cannot break it.
    @Test
    fun desktop2_sessionRestartPersists(): Unit = runBlocking {
        val appCtx = ApplicationProvider.getApplicationContext<Context>()
        appCtx.getSharedPreferences(appCtx.packageName + "_preferences", Context.MODE_PRIVATE)
            .edit().putString("touchMode", "3").commit()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val installer = DistroInstaller(appCtx)
        val distro = Distros.ALPINE
        if (!installer.isInstalled(distro)) {
            assertTrue("distro install failed", installer.install(distro) { })
        }
        val rootfs = installer.rootfsDir(distro)

        XServer.start(appCtx)
        val sock = File(XServer.socketDir(appCtx), "X0").absolutePath
        val deadline = System.currentTimeMillis() + 60_000
        var up = false
        while (System.currentTimeMillis() < deadline) {
            val ls = try { device.executeShellCommand("run-as dev.phonelinux.proto ls $sock 2>&1") } catch (t: Throwable) { "" }
            if (ls.trim() == sock) { up = true; break }
            Thread.sleep(1_000)
        }
        assertTrue("X socket never appeared", up)

        if (!GuestDesktop.hasDesktopShell(rootfs)) {
            var add = GuestDesktop.provisionDesktopShell(appCtx, rootfs)
            if (add.exitCode != 0) {
                Thread.sleep(5_000)
                add = GuestDesktop.provisionDesktopShell(appCtx, rootfs)
            }
            assertTrue("apk add desktop shell failed (exit " + add.exitCode + ")\n" + (add.stdout + add.stderr).takeLast(1200), add.exitCode == 0)
        }
        assertTrue("desktop shell missing after provisioning", GuestDesktop.hasDesktopShell(rootfs))

        val session = GuestDesktop.launchDesktopSession(appCtx, rootfs)
        try {
            appCtx.startActivity(
                Intent(appCtx, com.termux.x11.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Thread.sleep(25_000)
            fun desktopUp(): Boolean =
                GuestDesktop.guestOk(appCtx, rootfs, "pgrep -x jwm || pgrep -x openbox || pgrep -x icewm || pgrep -x fluxbox") &&
                    GuestDesktop.guestOk(appCtx, rootfs, "pgrep -x xterm || pgrep -x st")
            var w = System.currentTimeMillis() + 90_000
            var up1 = false
            while (System.currentTimeMillis() < w) { if (desktopUp()) { up1 = true; break }; Thread.sleep(3_000) }
            assertTrue("desktop never came up before restart test", up1 && session.isAlive)

            // Destroy, then prove the guest is actually down: destroy() may
            // orphan proot children, and leftovers would fake the restart.
            session.destroy()
            Thread.sleep(2_000)
            // Marker-based full-session kill: destroy() alone leaves orphan
            // X clients (a leftover xclock crossed a restart in run cd5dc6d);
            // a real close must take down the WHOLE session.
            val killed = GuestDesktop.killDesktopClients(appCtx, rootfs)
            Thread.sleep(1_000)
            val left = GuestDesktop.desktopClientCount(appCtx, rootfs)
            android.util.Log.i("PHONEDIAG", "restart teardown: killed=" + killed + " leftoverClients=" + left)
            assertTrue("session close left " + left + " guest clients alive (killed " + killed + ") - teardown is broken, a real close would leak", left == 0)
            assertTrue("distro marker lost after session destroy - install is not persistent",
                installer.isInstalled(distro))

            val session2 = GuestDesktop.launchDesktopSession(appCtx, rootfs)
            try {
                Thread.sleep(25_000)
                w = System.currentTimeMillis() + 90_000
                var up2 = false
                while (System.currentTimeMillis() < w) { if (desktopUp()) { up2 = true; break }; Thread.sleep(3_000) }
                android.util.Log.i("PHONEDIAG", "restart phase: desktopBack=" + up2 + " session2Alive=" + session2.isAlive)
                Shot.save("16-desktop-restart")
                val rsLog = try { device.executeShellCommand("run-as dev.phonelinux.proto tail -c 2000 files/guest-desktop.log 2>&1") } catch (t: Throwable) { "" }
                assertTrue("desktop did not return after session restart (session2Alive=" + session2.isAlive + "). Desktop log:\n" + rsLog, up2 && session2.isAlive)
            } finally {
                session2.destroy()
            }
        } finally {
            session.destroy()
            // Teardown must actually reap the guest tree: proot destroy
            // ORPHANS backgrounded tracees, and run 41186745767 showed
            // desktop-era survivors (st/pcmanfm/sh/xclock) feeding the
            // phantom cap until the 09:30:09 trim batch-killed the SHARED X
            // server (app_process 6486) with them. Never swallow the reap:
            // log killed + residual, retry once, leave survivors visible.
            try {
                val killed = GuestDesktop.killDesktopClients(appCtx, rootfs)
                var residual = GuestDesktop.desktopClientCount(appCtx, rootfs)
                if (residual > 0) {
                    Thread.sleep(2_000)
                    GuestDesktop.killDesktopClients(appCtx, rootfs)
                    residual = GuestDesktop.desktopClientCount(appCtx, rootfs)
                }
                android.util.Log.i("PHONEDIAG", "desktop teardown: killed=" + killed + " residual=" + residual)
            } catch (t: Throwable) {
                android.util.Log.w("PHONEDIAG", "desktop teardown reap failed: " + t)
            }
        }
    }
}
