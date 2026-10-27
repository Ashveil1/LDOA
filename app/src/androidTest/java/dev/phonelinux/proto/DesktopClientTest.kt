package dev.phonelinux.proto

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The GUI milestone: a REAL guest X client (xclock installed via apk inside
 * the proot guest) draws into the embedded X server through the bound
 * socket, displayed by the vendored lorie activity. Evidence: socket probe,
 * xclock stays alive, and 07-desktop-client.png captured via shell screencap
 * (inspected as a CI artifact).
 */
@RunWith(AndroidJUnit4::class)
class DesktopClientTest {

    @Test
    fun guestXclockDrawsThroughEmbeddedServer(): Unit = runBlocking {
        val appCtx = ApplicationProvider.getApplicationContext<Context>()
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
            android.util.Log.i("PHONEDIAG", "interaction phase skipped (wm=" + chosenWm + ", needs jwm)")
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
        appCtx.getSharedPreferences(appCtx.packageName + "_preferences", Context.MODE_PRIVATE)
            .edit().putString("touchMode", "3").commit()
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
        device.click(540, 800)
        Thread.sleep(1_000)
        val tapA = pointerPos()
        device.click(400, 750)
        Thread.sleep(1_000)
        val tapB = pointerPos()
        android.util.Log.i("PHONEDIAG", "calibration tapA(540,800)->" + tapA + " tapB(400,750)->" + tapB)
        assertTrue("direct-touch pref not in effect or pointer unreadable (tapA=" + tapA + " tapB=" + tapB + ")",
            tapA != null && tapB != null &&
                Math.abs(tapA.first - tapB.first) > 20 && Math.abs(tapA.second - tapB.second) > 10)

        // device -> X: X = sX*dx + oX, Y = sY*dy + oY
        // (JUnit assertTrue has no smart-cast contract: re-assert nullability.)
        val a = tapA!!; val b = tapB!!
        val sX = (a.first - b.first) / 140.0
        val sY = (a.second - b.second) / 50.0
        val oX = a.first - sX * 540
        val oY = a.second - sY * 800
        android.util.Log.i("PHONEDIAG", "calibrated map sX=" + sX + " oX=" + oX + " sY=" + sY + " oY=" + oY)
        fun devFor(x: Double, y: Double) = Pair(((x - oX) / sX).toInt(), ((y - oY) / sY).toInt())

        // 1. Tap the JWM menu button (tray = 1080x34+0+1978, button at the
        // left end ~X(20,1995)). jwm menus are override-redirect popups: a
        // new top-level window appears while the menu is open.
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
            val out = xwin("xwininfo -name script -stats | grep 'Absolute upper-left' | awk '{print ${'$'}NF}'")
            val n = out.lines().mapNotNull { it.trim().toIntOrNull() }
            return if (n.size >= 2) Pair(n[0], n[1]) else null
        }
        val stBefore = stPos()
        val dragFrom = devFor(546.0, 433.0)
        device.swipe(dragFrom.first, dragFrom.second, dragFrom.first + 200, dragFrom.second + 200, 40)
        Thread.sleep(2_000)
        Shot.save("10-st-dragged")
        val stAfter = stPos()
        android.util.Log.i("PHONEDIAG", "st drag from dev=" + dragFrom + " before=" + stBefore + " after=" + stAfter)
        assertTrue("st window did not move on title-bar drag (before=" + stBefore + " after=" + stAfter + ")",
            stBefore != null && stAfter != null &&
                (Math.abs(stAfter.first - stBefore.first) > 50 || Math.abs(stAfter.second - stBefore.second) > 50))
        } // jwm-only interaction phase

        } finally {
            // Leave a lighter system for later tests even when an assertion
            // above throws (a live desktop session feeds launcher ANR storms).
            session.destroy()
        }
    }
}
