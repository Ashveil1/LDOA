package dev.phonelinux.proto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * FOCUSED input-attribution harness (NOT a qualification lane): decides
 * what opens the Nautilus context menu on a stationary hold-release -
 * native GTK press-and-hold vs our lorie-mouse button-3 injection.
 * Evidence only: bootstrap facts are asserted, every experiment outcome is
 * LOGGED (harness: lines) and screenshotted, never asserted. A green job
 * means "harness executed", never "gesture qualified".
 *
 * Arms (clean menu state between arms, ESC + baseline-id verify):
 *  0. guest-side watcher survival check (background proot children died
 *     with their exec session in run 1a73f8f - validate before trusting)
 *  1. XTEST sanity: xdotool click 3 at the target -> visible menu reference
 *  2. toggle OFF (ldoa_longpress_rightclick=false, read live per event):
 *     hold 900ms -> any menu = NATIVE press-and-hold exists in this stack
 *  3. toggle ON: hold 900ms -> window + Map State + lifetime vs arm 2
 */
@RunWith(AndroidJUnit4::class)
class LdoaInputHarnessTest {
    private val TAG = "PHONELINUX_UBUNTU"

    @Test
    fun lorieMouseRightClickAttribution(): Unit = runBlocking {
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
        if (!GuestUbuntu.hasGnome(rootfs)) {
            val r = GuestUbuntu.provisionGnome(ctx, rootfs)
            assertTrue("gnome provision failed: ${r.stdout.takeLast(300)}", r.exitCode == 0)
        }
        Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
            "export DEBIAN_FRONTEND=noninteractive; apt-get install -y xterm"),
            fakeRoot = true)
        val kill = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
            "for f in /proc/[0-9]*/cmdline; do c=${'$'}(tr '\\0' ' ' < ${'$'}f 2>/dev/null); case ${'$'}c in *cmdline*) ;; *gnome-session*|*gnome-shell*) echo ${'$'}f | cut -d/ -f3;; esac; done | xargs -r kill 2>/dev/null; sleep 3; echo reset-done"))
        Log.i(TAG, "harness: session reset exit=${kill.exitCode}")
        XServer.start(ctx)
        val proc = XServer.launchOrReuseGnomeSession(ctx, d, rootfs)
        val logFile = File(ctx.filesDir, "guest-gnome.log")
        val deadline = System.currentTimeMillis() + 600_000
        var sawLaunchMarker = false
        while (System.currentTimeMillis() < deadline) {
            val text = if (logFile.isFile) logFile.readText() else ""
            if (text.contains("gnome-launch:")) sawLaunchMarker = true
            if (text.contains("gnome-launch: gnome-session alive") ||
                text.contains("gnome-launch: gnome-session died") ||
                text.contains("falling back to bare gnome-shell")) break
            if (text.contains("Oh no! Something has gone wrong") ||
                text.contains("Cannot open display") ||
                text.contains("gnome-shell: not found")) break
            if (!proc.isAlive) break
            Thread.sleep(10_000)
        }
        assertTrue("session script never reached the launch marker", sawLaunchMarker)
        assertTrue("gnome session process died before the evidence window closed", proc.isAlive)
        val instr = InstrumentationRegistry.getInstrumentation()
        val device = androidx.test.uiautomator.UiDevice.getInstance(instr)
        ctx.startActivity(
            android.content.Intent(ctx, com.termux.x11.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(30_000)
        Shot.dismissSystemDialogs(device)
        Thread.sleep(60_000)
        Shot.dismissSystemDialogs(device)
        assertTrue("gnome session died during the render window", proc.isAlive)
        // Desktop readiness: bare gnome-shell (the gnome-session fallback)
        // can take minutes to map the dock - run e98aa13 tapped at ~2 min
        // and hit unrendered wallpaper. Poll the X tree for shell windows
        // before trusting tap coordinates.
        fun xwinEarly(cmd: String): String {
            val r = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c", "export DISPLAY=:0; " + cmd),
                extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"))
            return r.stdout
        }
        val readyDeadline = System.currentTimeMillis() + 240_000
        var shellWins = 0
        while (System.currentTimeMillis() < readyDeadline) {
            shellWins = xwinEarly("xwininfo -root -tree 2>/dev/null | grep -ci 'gnome-shell' || true")
                .trim().toIntOrNull() ?: 0
            if (shellWins >= 3) break
            Thread.sleep(10_000)
        }
        Log.i(TAG, "harness: desktop readiness shell-windows=" + shellWins)
        Log.i(TAG, "harness: window tree at readiness: " +
            xwinEarly("xwininfo -root -children | tail -8").replace("\n", " | "))

        fun xwin(cmd: String): String {
            val r = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c", "export DISPLAY=:0; " + cmd),
                extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"))
            return r.stdout
        }
        fun windowIds(pattern: String): List<String> =
            xwin("xwininfo -root -tree | grep -i '" + pattern + "' | awk '{print ${'$'}1}'")
                .lines().filter { it.trimStart().startsWith("0x") }
        class NauWin(val id: String, val x: Int, val y: Int, val w: Int, val h: Int)
        fun nauVisible(): NauWin? {
            for (id in windowIds("nautilus")) {
                val st = xwin("xwininfo -id " + id + " -stats")
                val map = Regex("Map State: (\\w+)").find(st)?.groupValues?.get(1)
                val w = Regex("Width: (\\d+)").find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val h = Regex("Height: (\\d+)").find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (map == "IsViewable" && w > 200 && h > 200) {
                    val x = Regex("Absolute upper-left X:\\s+(-?\\d+)").find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val y = Regex("Absolute upper-left Y:\\s+(-?\\d+)").find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    return NauWin(id, x, y, w, h)
                }
            }
            return null
        }
        fun frameExtents(id: String): List<Int> {
            val out = xwin("xprop -id " + id + " _GTK_FRAME_EXTENTS _NET_FRAME_EXTENTS 2>/dev/null || true")
            val nums = Regex("-?\\d+").findAll(out.replace(Regex("_NET_FRAME_EXTENTS.*"), ""))
                .map { it.value.toInt() }.toList()
            return if (nums.size >= 4) nums.take(4) else listOf(0, 0, 0, 0)
        }
        fun rootChildIds(): Set<String> =
            xwin("xwininfo -root -children | awk '{print ${'$'}1}' | grep '^0x' || true")
                .split(Regex("\\s+")).filter { it.startsWith("0x") }.toSet()
        fun geomLine(id: String): String =
            xwin("xwininfo -id " + id + " | grep -E 'Map State|Absolute upper-left|Width:|Height:'").replace("\n", " | ")
        fun plausibleMenuGeom(id: String): Boolean {
            val g = geomLine(id)
            val w = Regex("Width:\\s+(\\d+)").find(g)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val h = Regex("Height:\\s+(\\d+)").find(g)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return w >= 20 && h >= 20
        }
        fun hideSoftKeyboardIfShown() {
            val dump = device.executeShellCommand("dumpsys input_method")
            if (dump.contains("mInputShown=true")) {
                device.pressBack()
                Thread.sleep(1_000)
            }
        }
        fun probeX(devX: Int, devY: Int): Pair<Int, Int>? {
            device.swipe(devX, devY, devX + 1, devY, 5)
            Thread.sleep(600)
            val out = xwin("xdotool getmouselocation --shell 2>/dev/null || true")
            val x = Regex("X=(-?\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
            val y = Regex("Y=(-?\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
            return if (x != null && y != null) Pair(x, y) else null
        }

        // Open Files from the dock (same proven taps as the spike lane).
        device.swipe(540, 200, 540, 60, 20)
        Thread.sleep(1_000)
        device.click(540, 1200)
        Thread.sleep(2_000)
        val beforeIds = windowIds("nautilus")
        var nauWid: String? = null
        for (round in 1..3) {
            for (tapY in listOf(194, 264)) {
                device.swipe(30, tapY, 32, tapY, 25)
                Log.i(TAG, "harness: pressed Files candidate (30,$tapY) round $round")
                val openDeadline = System.currentTimeMillis() + 120_000
                while (System.currentTimeMillis() < openDeadline) {
                    val vis = nauVisible()
                    if (vis != null && vis.id !in beforeIds) { nauWid = vis.id; break }
                    Thread.sleep(3_000)
                }
                if (nauWid != null) break
            }
            if (nauWid != null) break
            Log.i(TAG, "harness: window tree after failed round " + round + ": " +
                xwin("xwininfo -root -children | tail -10").replace("\n", " | "))
        }
        Thread.sleep(8_000)
        assertTrue("harness: Files window did not open", nauWid != null)
        val nw = nauVisible()
        assertTrue("harness: Files geometry unreadable", nw != null)
        hideSoftKeyboardIfShown()
        val p1 = probeX(300, 1200)
        val p2 = probeX(700, 1700)
        Log.i(TAG, "harness: mapping probes dev(300,1200)->$p1 dev(700,1700)->$p2")
        fun mapToDevice(x: Int, y: Int): Pair<Int, Int>? {
            val first = p1 ?: return null
            val second = p2 ?: return null
            if (Math.abs(second.first - first.first) < 10 ||
                Math.abs(second.second - first.second) < 10) return null
            val ax = (700 - 300).toDouble() / (second.first - first.first)
            val bx = 300 - ax * first.first
            val ay = (1700 - 1200).toDouble() / (second.second - first.second)
            val by = 1200 - ay * first.second
            return Pair((ax * x + bx).toInt(), (ay * y + by).toInt())
        }
        val fe = frameExtents(nauWid!!)
        val bgGuestX = nw!!.x + fe[0] + (nw.w - fe[0] - fe[1]) / 2
        val bgGuestY = nw.y + fe[2] + (nw.h - fe[2] - fe[3]) - 80
        val bgTarget = mapToDevice(bgGuestX, bgGuestY)
        assertTrue("harness: no device mapping for hold target", bgTarget != null)
        Log.i(TAG, "harness: hold target guest($bgGuestX,$bgGuestY) -> dev $bgTarget")
        Shot.save("h0-target")

        // Guest-side watcher (ms timestamps, independent of proot-exec
        // latency). Backgrounded proot children died with their exec
        // session in run 1a73f8f, so arm 0 VALIDATES survival before any
        // arm trusts it. setsid+nohup detach from the exec session.
        fun watchStart(arm: String): Long {
            xwin("xwininfo -root -children | awk '{print ${'$'}1}' | grep '^0x' | sort > /tmp/base-" + arm + ".ids; rm -f /tmp/watch-" + arm + ".log; " +
                "(setsid nohup /bin/sh -c 'for i in ${'$'}(seq 1 90); do xwininfo -root -children | awk \"{print \\\"${'$'}1\"}\" | grep ^0x | sort > /tmp/now-" + arm + ".ids; " +
                "comm -13 /tmp/base-" + arm + ".ids /tmp/now-" + arm + ".ids | while read id; do g=${'$'}(xwininfo -id ${'$'}id | grep -E \"Map State|Width:|Height:|Absolute upper-left\" | tr \"\\n\" \" \"); echo \"${'$'}(date +%s%3N) CREATE ${'$'}id ${'$'}g\" >> /tmp/watch-" + arm + ".log; done; " +
                "comm -23 /tmp/base-" + arm + ".ids /tmp/now-" + arm + ".ids | while read id; do echo \"${'$'}(date +%s%3N) DESTROY ${'$'}id\" >> /tmp/watch-" + arm + ".log; done; " +
                "mv /tmp/now-" + arm + ".ids /tmp/base-" + arm + ".ids; sleep 0.2; done' > /dev/null 2>&1 &)")
            return System.currentTimeMillis()
        }
        fun watchStop(arm: String): String {
            Thread.sleep(500)
            xwin("pkill -f 'seq 1 90' 2>/dev/null || true")
            Thread.sleep(300)
            return xwin("cat /tmp/watch-" + arm + ".log 2>/dev/null || true")
        }

        // Arm 0: watcher survival validation with REAL window churn (an
        // xterm maps and exits): if the backgrounded watcher died with its
        // proot exec session, the CREATE/DESTROY pair never gets logged and
        // the arms below rely on the pixel series instead.
        watchStart("arm0")
        Thread.sleep(1_000)
        xwin("(setsid nohup xterm -e /bin/true > /dev/null 2>&1 &)")
        Thread.sleep(4_000)
        val arm0 = watchStop("arm0")
        val watcherAlive = arm0.contains("CREATE")
        Log.i(TAG, "harness: arm0 watcher-survival=" + (if (watcherAlive) "ALIVE" else "DEAD") +
            " log=[" + arm0.trim().replace("\n", " || ") + "]" +
            (if (watcherAlive) "" else " - during-hold attribution falls back to the pixel series"))

        val prefs = ctx.getSharedPreferences(ctx.packageName + "_preferences", android.content.Context.MODE_PRIVATE)
        fun arm(arm: String, label: String, act: () -> Unit) {
            Log.i(TAG, "harness: arm=" + arm + " " + label + " START")
            val baseIds = rootChildIds()
            val w0 = watchStart(arm)
            act()
            val watchLog = watchStop(arm)
            Log.i(TAG, "harness: arm=" + arm + " watcher wallStart=" + w0 + " events:\n" + watchLog.trim())
            val postNew = (rootChildIds() - baseIds).sorted()
            Log.i(TAG, "harness: arm=" + arm + " post-arm new windows=" + postNew +
                postNew.map { " [" + it + "] " + geomLine(it) })
            Shot.save("h-" + arm + "-end")
            Log.i(TAG, "harness: arm=" + arm + " END")
        }
        fun escClean() {
            device.pressKeyCode(android.view.KeyEvent.KEYCODE_ESCAPE)
            Thread.sleep(1_500)
            xwin("xdotool key Escape 2>/dev/null || true")
            Thread.sleep(1_500)
        }

        // Arm 1: XTEST sanity - a synthetic button-3 through XTEST must open
        // a visible menu (reference behavior, run 1a73f8f pixel-proven).
        arm("arm1", "XTEST sanity xdotool click 3") {
            xwin("xdotool mousemove " + bgGuestX + " " + bgGuestY + " click 3 2>/dev/null || true")
            Thread.sleep(4_000)
            Shot.save("h-arm1-xtest-at-detection")
            Thread.sleep(6_000)
        }
        escClean()

        // Arm 2: toggle OFF - our injection disabled; any menu = NATIVE
        // GTK press-and-hold. Pref is read live per event by
        // TouchInputHandler (line 433), no restart needed.
        prefs.edit().putBoolean("ldoa_longpress_rightclick", false).commit()
        Log.i(TAG, "harness: toggle ldoa_longpress_rightclick=false committed")
        arm("arm2", "toggle OFF hold 900ms (native probe)") {
            val down = android.os.SystemClock.uptimeMillis()
            Log.i(TAG, "harness: arm2 DOWN at wall=" + System.currentTimeMillis())
            val props = android.view.MotionEvent.PointerProperties().apply {
                id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            }
            val coords = android.view.MotionEvent.PointerCoords().apply {
                x = bgTarget!!.first.toFloat(); y = bgTarget.second.toFloat(); pressure = 1f; size = 1f
            }
            fun ev(action: Int) = android.view.MotionEvent.obtain(down,
                android.os.SystemClock.uptimeMillis(), action, 1,
                arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            var e = ev(android.view.MotionEvent.ACTION_DOWN)
            try { instr.sendPointerSync(e) } finally { e.recycle() }
            Thread.sleep(900)
            Shot.save("h-arm2-during-hold")
            e = ev(android.view.MotionEvent.ACTION_UP)
            try { instr.sendPointerSync(e) } finally { e.recycle() }
            Log.i(TAG, "harness: arm2 UP at wall=" + System.currentTimeMillis())
            Thread.sleep(500)
            Shot.save("h-arm2-at-up-plus-500ms")
            Thread.sleep(3_000)
            Shot.save("h-arm2-at-up-plus-3s5")
            Thread.sleep(6_000)
        }
        escClean()

        // Arm 3: toggle ON - hold; menu window timing/Map State/lifetime vs
        // arm 2 attributes native vs injected.
        prefs.edit().putBoolean("ldoa_longpress_rightclick", true).commit()
        Log.i(TAG, "harness: toggle ldoa_longpress_rightclick=true committed")
        arm("arm3", "toggle ON hold 900ms (injection arm)") {
            val down = android.os.SystemClock.uptimeMillis()
            Log.i(TAG, "harness: arm3 DOWN at wall=" + System.currentTimeMillis())
            val props = android.view.MotionEvent.PointerProperties().apply {
                id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            }
            val coords = android.view.MotionEvent.PointerCoords().apply {
                x = bgTarget!!.first.toFloat(); y = bgTarget.second.toFloat(); pressure = 1f; size = 1f
            }
            fun ev(action: Int) = android.view.MotionEvent.obtain(down,
                android.os.SystemClock.uptimeMillis(), action, 1,
                arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            var e = ev(android.view.MotionEvent.ACTION_DOWN)
            try { instr.sendPointerSync(e) } finally { e.recycle() }
            Thread.sleep(900)
            Shot.save("h-arm3-during-hold")
            e = ev(android.view.MotionEvent.ACTION_UP)
            try { instr.sendPointerSync(e) } finally { e.recycle() }
            Log.i(TAG, "harness: arm3 UP at wall=" + System.currentTimeMillis())
            Thread.sleep(500)
            Shot.save("h-arm3-at-up-plus-500ms")
            Thread.sleep(3_000)
            Shot.save("h-arm3-at-up-plus-3s5")
            Thread.sleep(6_000)
        }
        escClean()
        Log.i(TAG, "harness: HARNESS-EVIDENCE-ONLY - no gesture qualification asserted; " +
            "attribute via arm2 (native) vs arm3 (injection) window create timing vs DOWN/UP wall times " +
            "and LDOA-HOLD-FIRE logcat lines; arm1 proves menus can be visible at all")
    }
}
