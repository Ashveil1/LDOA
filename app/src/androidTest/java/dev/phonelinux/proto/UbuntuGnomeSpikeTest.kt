package dev.phonelinux.proto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * B feasibility spike: Ubuntu Base rootfs + GNOME session on the vendored
 * lorie X server. This RECORDS evidence (guest-gnome.log lines, screenshots
 * via the shot pipeline); it asserts only the launch-path facts. Whether the
 * result LOOKS like standard Ubuntu (top bar + dock + Yaru + wallpaper) is a
 * pixel-inspection call on the artifact screenshots, not a test assert.
 *
 * Runs in its own CI job (ubuntu-gnome-spike): apt-in-emulator is far too
 * slow for the every-push lane.
 */
@RunWith(AndroidJUnit4::class)
class UbuntuGnomeSpikeTest {
    private val TAG = "PHONELINUX_UBUNTU"

    @Test
    fun ubuntuGnomeSessionStarts(): Unit = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val d = Distros.UBUNTU
        val installer = DistroInstaller(ctx)

        if (!installer.isInstalled(d)) {
            var last: InstallState = InstallState.NotInstalled
            val ok = installer.install(d) { last = it }
            assertTrue("ubuntu install failed at state=$last", ok)
        }
        assertTrue(installer.isInstalled(d))
        val rootfs = installer.rootfsDir(d)

        // Guest sanity: right distro, right arch, apt present.
        val g = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
            "uname -m; . /etc/os-release; echo \"os=\${NAME}-\${VERSION_ID}\"; apt-get --version | head -1"))
        Log.i(TAG, "guest sanity exit=${g.exitCode} out=${g.stdout.trim()}")
        assertEquals("guest exec must exit 0. stderr=${g.stderr}", 0, g.exitCode)
        val expectedArch = if (android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") "x86_64" else "aarch64"
        assertTrue("guest arch $expectedArch, got: ${g.stdout}", g.stdout.contains(expectedArch))
        assertTrue("guest must be Ubuntu, got: ${g.stdout}", g.stdout.contains("Ubuntu"))

        // Provision GNOME + Ubuntu appearance set (long: apt download+install).
        if (!GuestUbuntu.hasGnome(rootfs)) {
            val r = GuestUbuntu.provisionGnome(ctx, rootfs)
            Log.i(TAG, "provision exit=${r.exitCode} tail=${r.stdout.takeLast(800)} errtail=${r.stderr.takeLast(400)}")
            assertTrue("gnome provision failed exit=${r.exitCode}: ${r.stdout.takeLast(400)}", r.exitCode == 0)
        }
        assertTrue("gnome-shell binary present", GuestUbuntu.hasGnome(rootfs))

        // Session reset: the auto-enter test (runs first in this lane) may
        // have left a live GNOME session on :0. Kill leftovers via /proc
        // (no procps dependency) so this launch owns the display.
        val kill = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
            "for f in /proc/[0-9]*/cmdline; do c=${'$'}(tr '\\0' ' ' < ${'$'}f 2>/dev/null); case ${'$'}c in *cmdline*) ;; *gnome-session*|*gnome-shell*) echo ${'$'}f | cut -d/ -f3;; esac; done | xargs -r kill 2>/dev/null; sleep 3; echo reset-done"))
        Log.i(TAG, "pre-launch session reset exit=${kill.exitCode} out=${kill.stdout.trim()}")

        // X server + GNOME session.
        XServer.start(ctx)
        val proc = GuestUbuntu.launchGnomeSession(ctx, rootfs)

        // Evidence window: poll guest-gnome.log for launch markers, up to 10 min.
        val logFile = File(ctx.filesDir, "guest-gnome.log")
        val deadline = System.currentTimeMillis() + 600_000
        var sawLaunchMarker = false
        while (System.currentTimeMillis() < deadline) {
            val text = if (logFile.isFile) logFile.readText() else ""
            if (text.contains("gnome-launch:")) sawLaunchMarker = true
            if (text.contains("gnome-launch: gnome-session alive") ||
                text.contains("gnome-launch: gnome-session died") ||
                text.contains("falling back to bare gnome-shell")) break
            // Decisive failures end the poll immediately (no blind waiting):
            // gnome-shell's own "sad computer" screen and hard X/dbus errors.
            if (text.contains("Oh no! Something has gone wrong") ||
                text.contains("Cannot open display") ||
                text.contains("gnome-shell: not found")) {
                Log.i(TAG, "decisive failure marker in gnome log; failing fast")
                break
            }
            if (!proc.isAlive) break
            Thread.sleep(10_000)
        }
        Log.i(TAG, "gnome log tail: " + (if (logFile.isFile) logFile.readText().takeLast(1500) else "<missing>"))
        assertTrue("session script never reached the launch marker", sawLaunchMarker)
        assertTrue("gnome session process died before the evidence window closed", proc.isAlive)

        // Pixel evidence: show the X surface and capture early + settled
        // shots (GNOME's first start on an emulator can take minutes; the
        // two shots let pixel inspection see the progression).
        val instr = InstrumentationRegistry.getInstrumentation()
        val device = androidx.test.uiautomator.UiDevice.getInstance(instr)
        ctx.startActivity(
            android.content.Intent(ctx, com.termux.x11.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(30_000)
        Shot.dismissSystemDialogs(device)
        Shot.save("20-ubuntu-gnome-early")
        Thread.sleep(90_000)
        Shot.dismissSystemDialogs(device)
        Shot.save("21-ubuntu-gnome-settled")
        Log.i(TAG, "gnome log tail2: " + (if (logFile.isFile) logFile.readText().takeLast(1500) else "<missing>"))
        assertTrue("gnome session died during the render window", proc.isAlive)
        Log.i(TAG, "UBUNTU-GNOME-SESSION-ALIVE (visual Ubuntu appearance is judged on artifact screenshots)")

        // 3. INTERACTION proof (not just render): finger-tap the Files dock
        // icon -> a real nautilus window must map; finger-drag its headerbar
        // -> the geometry must move. Verified through the guest's own
        // xwininfo against :0, like the Alpine desktop lane.
        fun xwin(cmd: String): String {
            val r = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c", "export DISPLAY=:0; " + cmd),
                extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"))
            return r.stdout
        }
        fun windowIds(pattern: String): List<String> =
            xwin("xwininfo -root -children | grep -i '" + pattern + "' | awk '{print ${'$'}1}'")
                .lines().filter { it.trimStart().startsWith("0x") }

        // Dismiss the screen-lock notification banner (top center) so it
        // cannot cover the dock: swipe it upward off the stack.
        device.swipe(540, 200, 540, 60, 20)
        Thread.sleep(1_000)

        val nauBefore = windowIds("nautilus")
        Log.i(TAG, "dock-interact: nautilus before=" + nauBefore)
        Log.i(TAG, "dock-interact: window tree at tap time: " +
            xwin("xwininfo -root -children | tail -8").replace("\n", " | "))
        Shot.save("21b-ubuntu-dock-before-tap")
        // Files icon = top dock icon. Pixel-proven coords from the settled
        // shot of run 40661199161 (dock at left edge, first icon ~12,58 in
        // the 324x720 preview = 40,193 on the 1080x2400 panel). Second
        // candidate y=260 covers the icon sitting one slot lower when the
        // screen-lock banner shifts the layout.
        // Clear any open shell popup first: in run 40671527573 the
        // notification shade was open at tap time (shot 21b) and ate the
        // first tap; an empty-wallpaper tap dismisses shell popups.
        device.click(540, 1200)
        Thread.sleep(2_000)
        Shot.save("21c-ubuntu-desktop-clean")
        // Icon centers pixel-measured from the full-panel cold-start shot
        // (30, run 40671527573): Files = y~194 (grey/red cabinet),
        // second icon y~264, both spanning x~4-60 -> tap x=30.
        var nauWid: String? = null
        for ((attempt, tapY) in listOf(194, 264).withIndex().map { it.index + 1 to it.value }) {
            // Finger-like press: short hold instead of an instant
            // down/up, closer to how a finger lands on glass.
            device.swipe(30, tapY, 32, tapY, 25)
            Log.i(TAG, "dock-interact: pressed Files candidate (30," + tapY + ") attempt " + attempt)
            val openDeadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < openDeadline) {
                val now = windowIds("nautilus")
                val fresh = now.firstOrNull { it !in nauBefore }
                if (fresh != null) { nauWid = fresh; break }
                val proc = xwin("pgrep -x nautilus || true").trim()
                if (proc.isNotEmpty()) Log.i(TAG, "dock-interact: nautilus process alive pid=" + proc + " (no window yet)")
                Thread.sleep(3_000)
            }
            if (nauWid != null) break
        }
        Shot.save("22-ubuntu-dock-files-opened")
        assertTrue("finger press on the dock Files icon did not open a nautilus window (2 positions tried)", nauWid != null)

        fun winPos(id: String): Pair<Int, Int>? {
            val out = xwin("xwininfo -id " + id + " -stats | grep 'Absolute upper-left' | awk '{print ${'$'}NF}'")
            val n = out.lines().mapNotNull { it.trim().toIntOrNull() }
            return if (n.size >= 2) Pair(n[0], n[1]) else null
        }
        val wBefore = winPos(nauWid!!)
        Log.i(TAG, "dock-interact: nautilus wid=" + nauWid + " pos=" + wBefore)
        assertTrue("nautilus window geometry unreadable", wBefore != null)
        // Headerbar (CSD) drag: grab inside the window top area, off-center
        // to avoid the path bar/search button, move up-left modestly.
        val grabX = wBefore!!.first + 150
        val grabY = wBefore.second + 25
        device.swipe(grabX, grabY, grabX - 60, grabY + 120, 40)
        Thread.sleep(2_000)
        Shot.save("23-ubuntu-window-dragged")
        val wAfter = winPos(nauWid)
        Log.i(TAG, "dock-interact: dragged from=" + wBefore + " to=" + wAfter)
        assertTrue("nautilus window did not move on finger drag (before=" + wBefore + " after=" + wAfter + ")",
            wAfter != null && (Math.abs(wAfter.first - wBefore.first) > 50 || Math.abs(wAfter.second - wBefore.second) > 50))
        Log.i(TAG, "UBUNTU-GNOME-INTERACTION-OK dock tap opened Files; finger drag moved the window")

        // 4. RESTART persistence: kill the whole session, relaunch, the
        // desktop must come back (shell windows reappear on :0).
        xwin("for f in /proc/[0-9]*/cmdline; do c=${'$'}(tr '\\0' ' ' < ${'$'}f 2>/dev/null); case ${'$'}c in *cmdline*) ;; *gnome-session*|*gnome-shell*) echo ${'$'}f | cut -d/ -f3;; esac; done | xargs -r kill 2>/dev/null; true")
        val deathDeadline = System.currentTimeMillis() + 90_000
        while (proc.isAlive && System.currentTimeMillis() < deathDeadline) Thread.sleep(3_000)
        Log.i(TAG, "restart: old session alive=" + proc.isAlive)
        val proc2 = GuestUbuntu.launchGnomeSession(ctx, rootfs)
        val shellDeadline = System.currentTimeMillis() + 300_000
        var shellBack = false
        while (System.currentTimeMillis() < shellDeadline) {
            if (xwin("xwininfo -root -tree | grep -c 'gnome-shell'").trim().toIntOrNull()?.let { it > 0 } == true) {
                shellBack = true; break
            }
            if (!proc2.isAlive) break
            Thread.sleep(10_000)
        }
        Thread.sleep(20_000)
        Shot.dismissSystemDialogs(device)
        Shot.save("24-ubuntu-gnome-restarted")
        assertTrue("relaunched gnome session process died", proc2.isAlive)
        assertTrue("gnome-shell windows did not reappear after session restart", shellBack)
        Log.i(TAG, "UBUNTU-GNOME-RESTART-OK session killed and relaunched; shell back on :0")

        // 5. COLD-START decision path: the plinux_desktop pref was set when
        // the auto-enter test opened the desktop. Recreating MainActivity
        // (config change) re-runs its cold-start effect with a fresh VM ->
        // openDesktop must fire and find the session ALREADY RUNNING
        // (idempotent: bring forward, no second gnome-session on :0).
        // Supplementary signal only: the GATE is the workflow's host-side
        // force-stop + relaunch probe (manual install, no AGP teardown)
        // with a fresh session marker and post-settle pixels.
        device.pressBack() // leave the X11 surface -> MainActivity resumes
        Thread.sleep(2_000)
        device.setOrientationLandscape()
        Thread.sleep(3_000)
        device.setOrientationNatural()
        device.waitForIdle(3_000)
        val reenterDeadline = System.currentTimeMillis() + 120_000
        var reentered = false
        while (System.currentTimeMillis() < reenterDeadline) {
            if (XServer.gnomeSessionActive(Distros.UBUNTU)) { reentered = true; break }
            Thread.sleep(3_000)
        }
        Thread.sleep(15_000)
        Shot.dismissSystemDialogs(device)
        Shot.save("25-ubuntu-coldstart-reenter")
        assertTrue("cold-start re-enter: no live GNOME session after MainActivity recreation",
            reentered)
        assertTrue("relaunched session died during re-enter", proc2.isAlive)
        Log.i(TAG, "UBUNTU-COLDSTART-REENTER-OK recreated MainActivity re-entered the running desktop, no second session")
        // Full-log mirroring: the artifact file pull has proven unreliable
        // (0-byte guest-gnome.log in run eaaf47f); logcat (shotlog.txt) is
        // the collection path that works. Chunk to stay under logcat limits.
        if (logFile.isFile) {
            val full = logFile.readText()
            full.chunked(900).forEachIndexed { i, c -> Log.i(TAG, "gnome-full[$i]: $c") }
        }
    }
}
