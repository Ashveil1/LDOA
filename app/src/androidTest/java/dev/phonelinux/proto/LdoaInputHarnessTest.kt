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
 * FOCUSED gesture verification harness (NOT a qualification lane): verifies
 * the native-first touch model in a warm GNOME session. Evidence only:
 * bootstrap facts are asserted, every experiment outcome is LOGGED
 * (harness: lines) and screenshotted, never asserted. A green job means
 * "harness executed", never "gesture qualified".
 *
 * Model under test (native-first, user-delegated UX pick 2026-10-29):
 *   tap = click; 1-finger drag native (scroll / window move / selection);
 *   1-finger hold = NATIVE GTK press-and-hold (menu at the timeout, text
 *   selection preserved); 2-finger tap = injected right-click everywhere;
 *   2-finger drag = wheel scroll for legacy clients.
 *
 * Arms (clean menu state between arms, ESC + synchronous window diffs):
 *  1. XTEST sanity: xdotool click 3 at the target -> visible menu reference
 *  2. native 1-finger hold 1200ms -> menu DURING hold, persists after
 *     release (native GTK press-and-hold; no injection path exists anymore)
 *  3. 2-finger tap -> injected right-click menu appears AND persists.
 *     The click is injected when the LAST finger lifts: injecting on the
 *     second finger's lift left a touch active and the trailing lift
 *     dismissed the menu (run 40851575540 arm3; deferred-fire fix).
 *  3b. pending-timeout cancel: second finger lifts inside the window,
 *     last finger stays down >350ms -> NO fire, no menu
 *  3c. pending + last-finger drag -> drag stays a native 1-finger drag,
 *     the lift does NOT fire (no residual actuation)
 *  4. 2-finger drag -> NO menu (movement cancels the tap: cancel proof)
 *  5. 2-finger hold 1000ms -> NO menu (timeout cancels the tap: proof)
 *  6. GTK scroll on REAL content: the open Files window is navigated to
 *     a 300-file folder via XTEST (setup only), then 1-finger drag
 *     (native touch scroll) + 2-finger drag (wheel injection), pixel
 *     before/after. No dbus GApplication handoff needed.
 *  7. GTK text selection best-effort: Ctrl+L location entry, typed text,
 *     1-finger hold on the text -> pixel evidence of selection UI
 *  8. legacy xev (HELD proot process: backgrounded guest children die
 *     with their exec session, runs 1a73f8f/40844558720): button-3 and
 *     wheel DELIVERY proof from xev logs. This is delivery ONLY - a
 *     legacy client's visible outcome (selection extend, scrollback
 *     movement) is NOT proven here and remains a disclosed trial
 *     limitation. xterm was dropped: no pty under proot (ENOSYS).
 *
 * The guest-side window watcher was REMOVED: backgrounded proot children
 * die with their exec session, so its zero-count logs were void evidence.
 * Window attribution uses synchronous before/after root-window diffs.
 */
@RunWith(AndroidJUnit4::class)
class LdoaInputHarnessTest {
    private val TAG = "PHONELINUX_UBUNTU"

    // JUnit timeout: an unbounded guest call (Proot.runGuest waitFor has no
    // timeout) must surface as an ATTRIBUTED failure with the stuck stack,
    // not a silent 45+ min invocation stall (runs 41035740162 / 41043553840).
    // 20 min covers the worst-case bounded path (~10 min launch watch +
    // ~4 min readiness + sleeps) with headroom; it cuts no ordinary work.
    @Test(timeout = 1_200_000)
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
            "export DEBIAN_FRONTEND=noninteractive; apt-get install -y x11-utils xdotool"),
            fakeRoot = true)
        Log.i(TAG, "harness: ENTER lorieMouseRightClickAttribution (touchMode=3 pinned parked path)")
        val kill = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
            "for f in /proc/[0-9]*/cmdline; do c=${'$'}(tr '\\0' ' ' < ${'$'}f 2>/dev/null); case ${'$'}c in *cmdline*) ;; *gnome-session*|*gnome-shell*) echo ${'$'}f | cut -d/ -f3;; esac; done | xargs -r kill 2>/dev/null; sleep 3; echo reset-done"))
        Log.i(TAG, "harness: session reset exit=${kill.exitCode}")
        // These arms test the PARKED direct-mode injection (deferred-fire
        // two-finger tap). The product default is simulated touch since the
        // 2026-10-29 23:19 approved plan, so pin the mode explicitly or the
        // arms would silently test the stock simulated path instead.
        // Simulated-mode verification arms are the follow-up increment.
        ctx.getSharedPreferences(ctx.packageName + "_preferences", android.content.Context.MODE_PRIVATE)
            .edit().putString("touchMode", "3").apply()
        XServer.start(ctx)
        val proc = XServer.launchOrReuseGnomeSession(ctx, d, rootfs)
        val ledger = java.io.File(ctx.filesDir, "harness-owned-ledger.txt")
        fun ledgerNote(tag: String, pr: Process) {
            val pid = try {
                val m = pr.javaClass.methods.firstOrNull { it.name == "pid" && it.parameterCount == 0 }
                (m?.invoke(pr) as? Number)?.toLong() ?: -1L
            } catch (t: Throwable) { -1L }
            val start = try { java.io.File("/proc/" + pid + "/stat").readText().split(" ")[21] } catch (t: Throwable) { "?" }
            val uid = try { java.io.File("/proc/" + pid + "/status").readLines().first { it.startsWith("Uid:") } } catch (t: Throwable) { "?" }
            ledger.appendText(tag + " pid=" + pid + " start=" + start + " " + uid + " alive=" + pr.isAlive + " wall=" + System.currentTimeMillis() + "\n")
        }
        // Owned-process ledger written as spawns happen, NOT only in finally:
        // a JUnit timeout interrupts the test thread but cannot interrupt a
        // stuck native waitFor, so the finally below may never run - the
        // ledger is the post-mortem survivor record either way.
        ledger.writeText("harness: owned ledger begin wall=" + System.currentTimeMillis() + "\n")
        try {
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
                extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"),
                timeoutMs = 60_000)
            return r.stdout
        }
        val readyDeadline = System.currentTimeMillis() + 240_000
        var shellWins = 0
        while (System.currentTimeMillis() < readyDeadline) {
            shellWins = xwinEarly("xwininfo -root -tree 2>/dev/null | grep -ciE 'gnome.shell|gnome-shell' || true")
                .trim().toIntOrNull() ?: 0
            if (shellWins >= 3) break
            Thread.sleep(10_000)
        }
        Log.i(TAG, "harness: desktop readiness shell-windows=" + shellWins + " (readiness PROXY, not proved-usable desktop)")
        Log.i(TAG, "harness: window tree at readiness: " +
            xwinEarly("xwininfo -root -children | tail -8").replace("\n", " | "))
        // Warm-precondition evidence: which session services the auto-enter
        // session left running after the reset (reset kills only
        // gnome-session/gnome-shell cmdlines), and the launch fallback used.
        Log.i(TAG, "harness: services-retained gsd-count=" +
            xwinEarly("ps -ef 2>/dev/null | grep '[g]sd-' | wc -l").trim() +
            " shell-name-owner=" + xwinEarly("dbus-send --session --dest=org.freedesktop.DBus --print-reply /org/freedesktop/DBus org.freedesktop.DBus.NameHasOwner string:org.gnome.Shell 2>/dev/null | tail -1 || true").trim() +
            " a11y-bus=" + xwinEarly("dbus-send --session --dest=org.freedesktop.DBus --print-reply /org/freedesktop/DBus org.freedesktop.DBus.NameHasOwner string:org.a11y.Bus 2>/dev/null | tail -1 || true").trim())
        Log.i(TAG, "harness: launch-mode=" + (if (logFile.isFile && logFile.readText().contains("falling back to bare gnome-shell")) "gnome-session exit=1 fallback -> bare gnome-shell --x11" else "gnome-session"))
        Log.i(TAG, "harness: gtk-version=" + xwinEarly("dpkg -l libgtk-4-1 2>/dev/null | tail -1 || true").trim())
        Log.i(TAG, "harness: gtk-settings-ini gtk4=[" + xwinEarly("cat /root/.config/gtk-4.0/settings.ini 2>/dev/null || echo absent").replace("\n", " | ") +
            "] gtk3=[" + xwinEarly("cat /root/.config/gtk-3.0/settings.ini 2>/dev/null || echo absent").replace("\n", " | ") + "]")
        Log.i(TAG, "harness: gtk4-query-settings=" + xwinEarly("gtk4-query-settings 2>/dev/null | grep -i long-press || echo unavailable").trim())

        // Bounded (60s): fire-and-forget guest launches keep proot alive
        // while the backgrounded app runs, so stdout never EOFs (run
        // 41176270704: this arm wedged 14 min on the nautilus launch until
        // the JUnit timeout). On timeout the launch is verified separately
        // by the visibility poll below; a short query returning TIMED_OUT
        // is a real guest failure, now bounded and diagnosable.
        fun xwin(cmd: String): String {
            val r = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c", "export DISPLAY=:0; " + cmd),
                extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix"),
                timeoutMs = 60_000)
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

        // Settle taps kept from the spike-lane flow (swallowed under the
        // pinned mode below; they only pace the desktop).
        device.swipe(540, 200, 540, 60, 20)
        Thread.sleep(1_000)
        device.click(540, 1200)
        Thread.sleep(2_000)
        val beforeIds = windowIds("nautilus")
        var nauWid: String? = null
        // Assisted SETUP only (never product fresh-entry evidence, and no
        // dock/touch readiness credit): touchMode is pinned to "3" (TOUCH ->
        // NullInputStrategy: every finger event swallowed) for the parked-mode
        // arms below, so UiAutomator dock taps can never open Files in this
        // flow (deterministic failure in runs 41018478245+). Launch nautilus
        // via guest exec instead - mode-independent, log+pid preserved.
        xwin("( nautilus >/root/harness-nautilus-setup.log 2>&1 & echo ${'$'}! > /root/harness-nautilus-setup.pid )")
        val setupOpenDeadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < setupOpenDeadline) {
            val vis = nauVisible()
            if (vis != null && vis.id !in beforeIds) { nauWid = vis.id; break }
            Thread.sleep(3_000)
        }
        Log.i(TAG, "harness: assisted-setup guest-exec nautilus opened=" + (nauWid != null) +
            " pid=" + xwin("cat /root/harness-nautilus-setup.pid 2>/dev/null || echo unknown").trim() +
            " pid-state=" + xwin("NP=${'$'}(cat /root/harness-nautilus-setup.pid 2>/dev/null); if kill -0 ${'$'}NP 2>/dev/null; then echo alive; else echo dead; fi").trim() +
            " log-tail=[" + xwin("tail -3 /root/harness-nautilus-setup.log 2>/dev/null || echo none").replace("\n", " | ") + "]" +
            " - diagnostic setup only, no product readiness credit")
        if (nauWid == null) {
            Log.i(TAG, "harness: window tree after failed guest-exec setup: " +
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

        val prefs = ctx.getSharedPreferences(ctx.packageName + "_preferences", android.content.Context.MODE_PRIVATE)
        Log.i(TAG, "harness: pref read-back ldoa_twofinger_tap_rightclick=" +
            prefs.getBoolean("ldoa_twofinger_tap_rightclick", true) +
            " ldoa_twofinger_scroll=" + prefs.getBoolean("ldoa_twofinger_scroll", true))

        fun arm(arm: String, label: String, act: () -> Unit) {
            Log.i(TAG, "harness: arm=" + arm + " " + label + " START")
            val baseIds = rootChildIds()
            act()
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

        // ---- MotionEvent helpers (multi-pointer sequences) ----
        fun props(id: Int) = android.view.MotionEvent.PointerProperties().apply {
            this.id = id; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
        }
        fun coords(x: Float, y: Float) = android.view.MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1f; size = 1f
        }
        fun ev1(down: Long, action: Int, x: Float, y: Float) = android.view.MotionEvent.obtain(down,
            android.os.SystemClock.uptimeMillis(), action, 1,
            arrayOf(props(0)), arrayOf(coords(x, y)), 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        fun ev2(down: Long, action: Int, actionIndex: Int,
                x0: Float, y0: Float, x1: Float, y1: Float): android.view.MotionEvent {
            val act = if (action == android.view.MotionEvent.ACTION_POINTER_DOWN ||
                action == android.view.MotionEvent.ACTION_POINTER_UP)
                action or (actionIndex shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
            return android.view.MotionEvent.obtain(down,
                android.os.SystemClock.uptimeMillis(), act, 2,
                arrayOf(props(0), props(1)), arrayOf(coords(x0, y0), coords(x1, y1)), 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        fun send(e: android.view.MotionEvent) { try { instr.sendPointerSync(e) } finally { e.recycle() } }
        fun tap(x: Float, y: Float) {
            val down = android.os.SystemClock.uptimeMillis()
            send(ev1(down, android.view.MotionEvent.ACTION_DOWN, x, y))
            Thread.sleep(60)
            send(ev1(down, android.view.MotionEvent.ACTION_UP, x, y))
        }
        fun oneFingerHold(x: Float, y: Float, holdMs: Long, tag: String, baseIds: Set<String>) {
            val down = android.os.SystemClock.uptimeMillis()
            Log.i(TAG, "harness: " + tag + " DOWN at wall=" + System.currentTimeMillis())
            send(ev1(down, android.view.MotionEvent.ACTION_DOWN, x, y))
            Thread.sleep(holdMs)
            Shot.save("h-" + tag + "-during-hold")
            val duringNew = (rootChildIds() - baseIds).sorted()
            Log.i(TAG, "harness: " + tag + " during-hold new windows=" + duringNew +
                duringNew.map { " [" + it + "] " + geomLine(it) })
            send(ev1(down, android.view.MotionEvent.ACTION_UP, x, y))
            Log.i(TAG, "harness: " + tag + " UP at wall=" + System.currentTimeMillis())
        }
        fun twoFingerTap(x: Float, y: Float, tag: String) {
            val down = android.os.SystemClock.uptimeMillis()
            Log.i(TAG, "harness: " + tag + " 2ftap DOWN at wall=" + System.currentTimeMillis())
            send(ev1(down, android.view.MotionEvent.ACTION_DOWN, x, y))
            Thread.sleep(60)
            send(ev2(down, android.view.MotionEvent.ACTION_POINTER_DOWN, 1, x, y, x + 60f, y))
            Thread.sleep(120)
            send(ev2(down, android.view.MotionEvent.ACTION_POINTER_UP, 1, x, y, x + 60f, y))
            Thread.sleep(40)
            send(ev1(down, android.view.MotionEvent.ACTION_UP, x, y))
            Log.i(TAG, "harness: " + tag + " 2ftap UP at wall=" + System.currentTimeMillis())
        }
        fun twoFingerDrag(x: Float, y: Float, dy: Float, tag: String) {
            val down = android.os.SystemClock.uptimeMillis()
            Log.i(TAG, "harness: " + tag + " 2fdrag DOWN at wall=" + System.currentTimeMillis())
            send(ev1(down, android.view.MotionEvent.ACTION_DOWN, x, y))
            Thread.sleep(80)
            send(ev2(down, android.view.MotionEvent.ACTION_POINTER_DOWN, 1, x, y, x + 60f, y))
            Thread.sleep(80)
            val steps = 8
            for (i in 1..steps) {
                send(ev2(down, android.view.MotionEvent.ACTION_MOVE, 0,
                    x, y + dy * i / steps, x + 60f, y + dy * i / steps))
                Thread.sleep(50)
            }
            send(ev2(down, android.view.MotionEvent.ACTION_POINTER_UP, 1, x, y + dy, x + 60f, y + dy))
            Thread.sleep(40)
            send(ev1(down, android.view.MotionEvent.ACTION_UP, x, y + dy))
            Log.i(TAG, "harness: " + tag + " 2fdrag UP at wall=" + System.currentTimeMillis())
        }
        fun twoFingerHold(x: Float, y: Float, holdMs: Long, tag: String) {
            val down = android.os.SystemClock.uptimeMillis()
            Log.i(TAG, "harness: " + tag + " 2fhold DOWN at wall=" + System.currentTimeMillis())
            send(ev1(down, android.view.MotionEvent.ACTION_DOWN, x, y))
            Thread.sleep(60)
            send(ev2(down, android.view.MotionEvent.ACTION_POINTER_DOWN, 1, x, y, x + 60f, y))
            Thread.sleep(holdMs)
            send(ev2(down, android.view.MotionEvent.ACTION_POINTER_UP, 1, x, y, x + 60f, y))
            Thread.sleep(40)
            send(ev1(down, android.view.MotionEvent.ACTION_UP, x, y))
            Log.i(TAG, "harness: " + tag + " 2fhold UP at wall=" + System.currentTimeMillis())
        }
        fun oneFingerDrag(x: Float, y: Float, dx: Float, dy: Float, tag: String) {
            val down = android.os.SystemClock.uptimeMillis()
            Log.i(TAG, "harness: " + tag + " 1fdrag DOWN at wall=" + System.currentTimeMillis())
            send(ev1(down, android.view.MotionEvent.ACTION_DOWN, x, y))
            val steps = 10
            for (i in 1..steps) {
                send(ev1(down, android.view.MotionEvent.ACTION_MOVE, x + dx * i / steps, y + dy * i / steps))
                Thread.sleep(40)
            }
            send(ev1(down, android.view.MotionEvent.ACTION_UP, x + dx, y + dy))
            Log.i(TAG, "harness: " + tag + " 1fdrag UP at wall=" + System.currentTimeMillis())
        }
        fun keyEvent(code: Int, action: Int) {
            val now = android.os.SystemClock.uptimeMillis()
            instr.sendKeySync(android.view.KeyEvent(now, now, action, code, 0))
        }
        fun typeKeys(codes: List<Int>) {
            for (c in codes) {
                keyEvent(c, android.view.KeyEvent.ACTION_DOWN)
                keyEvent(c, android.view.KeyEvent.ACTION_UP)
                Thread.sleep(100)
            }
        }
        fun winGeom(id: String): NauWin? {
            val st = xwin("xwininfo -id " + id + " -stats")
            val map = Regex("Map State: (\\w+)").find(st)?.groupValues?.get(1)
            val w = Regex("Width: (\\d+)").find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val h = Regex("Height: (\\d+)").find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (map != "IsViewable") return null
            val x = Regex("Absolute upper-left X:\\s+(-?\\d+)").find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val y = Regex("Absolute upper-left Y:\\s+(-?\\d+)").find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return NauWin(id, x, y, w, h)
        }
        fun menuCheck(tag: String, pre: Set<String>, moment: String) {
            val newWins = (rootChildIds() - pre).sorted()
            Log.i(TAG, "harness: " + tag + " " + moment + " new windows=" + newWins +
                newWins.map { " [" + it + "] " + geomLine(it) })
        }

        // LDOA-2FTAP-FIRE corroboration from the device logcat (shell
        // rights via UiAutomation). -1 = unreadable.
        fun fireCount(): Int = try {
            Regex("LDOA-2FTAP-FIRE").findAll(
                device.executeShellCommand("logcat -d -s LorieTouch:I")).count()
        } catch (e: Exception) { -1 }

        val bx = bgTarget!!.first.toFloat()
        val by = bgTarget.second.toFloat()

        // Arm 1: XTEST sanity - a synthetic button-3 through XTEST must open
        // a visible menu (reference behavior, run 1a73f8f pixel-proven).
        arm("arm1", "XTEST sanity xdotool click 3") {
            xwin("xdotool mousemove " + bgGuestX + " " + bgGuestY + " click 3 2>/dev/null || true")
            Thread.sleep(4_000)
            Shot.save("h-arm1-xtest-at-detection")
            Thread.sleep(6_000)
        }
        escClean()

        // Arm 2: native 1-finger hold. No injection path exists for a
        // one-finger hold anymore, so any menu is NATIVE GTK press-and-hold.
        // Expected model behavior: menu appears DURING the hold (native
        // timeout) and persists after release (a real menu, not a flash).
        arm("arm2", "native 1-finger hold 1200ms (no injection exists for 1-finger hold)") {
            val pre = rootChildIds()
            oneFingerHold(bx, by, 1_200, "arm2", pre)
            Thread.sleep(500)
            Shot.save("h-arm2-at-up-plus-500ms")
            menuCheck("arm2", pre, "at up+0.5s")
            Thread.sleep(4_500)
            Shot.save("h-arm2-at-up-plus-5s")
            menuCheck("arm2", pre, "at up+5s (persistence)")
        }
        escClean()

        // Arm 3: 2-finger tap -> injected right-click. Expect exactly one
        // menu (no native 2-finger-tap menu exists in GTK) that PERSISTS
        // (the arm3 run-40844558720 failure mode was a menu that flashed
        // and vanished under the old duplicated injection).
        arm("arm3", "2-finger tap -> injected right-click, persistence (deferred fire)") {
            val pre = rootChildIds()
            val firesBefore = fireCount()
            twoFingerTap(bx, by, "arm3")
            Thread.sleep(1_000)
            Shot.save("h-arm3-at-tap-plus-1s")
            menuCheck("arm3", pre, "at tap+1s")
            Thread.sleep(5_000)
            Shot.save("h-arm3-at-tap-plus-6s")
            menuCheck("arm3", pre, "at tap+6s (persistence)")
            Log.i(TAG, "harness: arm3 FIRE-DELTA=" + (fireCount() - firesBefore) + " EXPECT-1")
        }
        escClean()

        // Arm 3b: pending-timeout cancel. The second finger lifts inside
        // the tap window (tap becomes PENDING), but the last finger stays
        // down past the 350ms final-lift window: no click may fire.
        arm("arm3b", "2ftap pending-timeout cancel (late last lift, EXPECT no fire)") {
            val pre = rootChildIds()
            val firesBefore = fireCount()
            val down = android.os.SystemClock.uptimeMillis()
            send(ev1(down, android.view.MotionEvent.ACTION_DOWN, bx, by))
            Thread.sleep(60)
            send(ev2(down, android.view.MotionEvent.ACTION_POINTER_DOWN, 1, bx, by, bx + 60f, by))
            Thread.sleep(120)
            send(ev2(down, android.view.MotionEvent.ACTION_POINTER_UP, 1, bx, by, bx + 60f, by))
            Thread.sleep(600)
            send(ev1(down, android.view.MotionEvent.ACTION_UP, bx, by))
            Thread.sleep(2_000)
            val spurious = (rootChildIds() - pre).filter { plausibleMenuGeom(it) }
            Log.i(TAG, "harness: arm3b pending-timeout FIRE-DELTA=" + (fireCount() - firesBefore) +
                " EXPECT-0 spurious=" + spurious +
                if (spurious.isEmpty()) " CANCEL-OK" else " SPURIOUS-RIGHT-CLICK")
            Shot.save("h-arm3b-after-late-lift")
        }
        escClean()

        // Arm 3c: pending + last-finger drag cancel, no residual
        // actuation. The second finger lifts inside the window (PENDING),
        // then the remaining finger drags beyond slop: the drag must stay
        // a native one-finger drag and the lift must NOT fire.
        arm("arm3c", "2ftap pending + drag cancel (EXPECT no fire, native drag)") {
            val pre = rootChildIds()
            val firesBefore = fireCount()
            val down = android.os.SystemClock.uptimeMillis()
            send(ev1(down, android.view.MotionEvent.ACTION_DOWN, bx, by))
            Thread.sleep(60)
            send(ev2(down, android.view.MotionEvent.ACTION_POINTER_DOWN, 1, bx, by, bx + 60f, by))
            Thread.sleep(120)
            send(ev2(down, android.view.MotionEvent.ACTION_POINTER_UP, 1, bx, by, bx + 60f, by))
            Thread.sleep(60)
            for (i in 1..6) {
                send(ev1(down, android.view.MotionEvent.ACTION_MOVE, bx + 25f * i, by))
                Thread.sleep(40)
            }
            send(ev1(down, android.view.MotionEvent.ACTION_UP, bx + 150f, by))
            Thread.sleep(2_000)
            val spurious = (rootChildIds() - pre).filter { plausibleMenuGeom(it) }
            Log.i(TAG, "harness: arm3c pending-drag FIRE-DELTA=" + (fireCount() - firesBefore) +
                " EXPECT-0 spurious=" + spurious +
                if (spurious.isEmpty()) " CANCEL-OK" else " SPURIOUS-RIGHT-CLICK")
            Shot.save("h-arm3c-after-pending-drag")
        }
        escClean()

        // Arm 4: 2-finger DRAG must NOT fire the tap right-click (movement
        // cancels). Any new menu-like window here = spurious right-click.
        arm("arm4", "2-finger drag cancel-safety (no menu may appear)") {
            val pre = rootChildIds()
            twoFingerDrag(bx, by, -300f, "arm4")
            Thread.sleep(2_000)
            val spurious = (rootChildIds() - pre).filter { plausibleMenuGeom(it) }
            Log.i(TAG, "harness: arm4 cancel-check spurious=" + spurious +
                if (spurious.isEmpty()) " CANCEL-OK" else " SPURIOUS-RIGHT-CLICK")
            Shot.save("h-arm4-after-drag")
        }
        escClean()

        // Arm 5: 2-finger HOLD (both fingers down 1000ms, no movement) must
        // NOT fire (350ms tap timeout cancels).
        arm("arm5", "2-finger hold cancel-safety (no menu may appear)") {
            val pre = rootChildIds()
            twoFingerHold(bx, by, 1_000, "arm5")
            Thread.sleep(2_000)
            val spurious = (rootChildIds() - pre).filter { plausibleMenuGeom(it) }
            Log.i(TAG, "harness: arm5 cancel-check spurious=" + spurious +
                if (spurious.isEmpty()) " CANCEL-OK" else " SPURIOUS-RIGHT-CLICK")
            Shot.save("h-arm5-after-hold")
        }
        escClean()

        // Arm 6: GTK scroll outcomes on REAL content. The already-open
        // Files window is navigated to a 300-file folder via XTEST
        // (xdotool ctrl+l + type + Return = setup mechanics, never pass
        // evidence). This sidesteps the GApplication dbus handoff that
        // silently failed in run 40851575540: the harness xwin env has no
        // session bus address, and a backgrounded dbus-run-session child
        // would die with its exec session. Gestures themselves are real
        // touch events; pixel before/after must show content movement.
        arm("arm6", "GTK scroll: 1-finger native + 2-finger wheel on real content") {
            Log.i(TAG, "harness: arm6 populate=" + xwin(
                "mkdir -p /root/scrolltest && for i in ${'$'}(seq 1 300); do : > /root/scrolltest/file-${'$'}i.txt; done && echo scrolltest-ready").trim())
            tap(bx, by)  // focus the Files window
            Thread.sleep(800)
            Log.i(TAG, "harness: arm6 navigate=" + xwin(
                "xdotool key ctrl+l && sleep 1 && xdotool type --delay 25 '/root/scrolltest' && sleep 1 && xdotool key Return && echo nav-sent").trim())
            Thread.sleep(4_000)
            Shot.save("h-arm6-folder-loaded")
            val sw = nw!!
            val c = mapToDevice(sw.x + sw.w / 2, sw.y + sw.h / 2)
            if (c != null) {
                oneFingerDrag(c.first.toFloat(), c.second.toFloat(), 0f, -400f, "arm6-1f")
                Thread.sleep(1_500)
                Shot.save("h-arm6-after-1f-drag")
                twoFingerDrag(c.first.toFloat(), c.second.toFloat(), 400f, "arm6-2f")
                Thread.sleep(1_500)
                Shot.save("h-arm6-after-2f-drag")
            } else Log.i(TAG, "harness: arm6 no device mapping for Files window")
        }
        escClean()

        // Arm 7: GTK text selection, best-effort. Ctrl+L opens the Files
        // location entry (explicit modifier down/up events: meta-only
        // KeyEvents never press X modifiers on lorie); type text, then a
        // 1-finger hold on the typed text. Native GTK entry press-and-hold
        // should show selection UI. Pixel evidence only, position of the
        // entry is estimated from the header bar.
        arm("arm7", "GTK text selection best-effort (location entry)") {
            tap(bx, by)  // ensure the Files window has focus
            Thread.sleep(800)
            keyEvent(android.view.KeyEvent.KEYCODE_CTRL_LEFT, android.view.KeyEvent.ACTION_DOWN)
            keyEvent(android.view.KeyEvent.KEYCODE_L, android.view.KeyEvent.ACTION_DOWN)
            keyEvent(android.view.KeyEvent.KEYCODE_L, android.view.KeyEvent.ACTION_UP)
            keyEvent(android.view.KeyEvent.KEYCODE_CTRL_LEFT, android.view.KeyEvent.ACTION_UP)
            Thread.sleep(1_000)
            typeKeys(listOf(android.view.KeyEvent.KEYCODE_E, android.view.KeyEvent.KEYCODE_T,
                android.view.KeyEvent.KEYCODE_C))
            Thread.sleep(800)
            Shot.save("h-arm7-entry-typed")
            val entryY = nw!!.y + fe[2] + 24
            val entryTarget = mapToDevice(bgGuestX, entryY)
            Log.i(TAG, "harness: arm7 entry target guest(" + bgGuestX + "," + entryY + ") -> dev " + entryTarget)
            if (entryTarget != null) {
                val pre = rootChildIds()
                oneFingerHold(entryTarget.first.toFloat(), entryTarget.second.toFloat(), 1_200, "arm7", pre)
                Thread.sleep(1_000)
                Shot.save("h-arm7-after-hold")
                menuCheck("arm7", pre, "after entry hold")
            }
        }
        escClean()

        // Arm 8: legacy-client DELIVERY proof only. xev (held proot
        // process: backgrounded guest children die with their exec
        // session) logs every button event it receives; xterm was
        // dropped (no pty under proot, ENOSYS - no endless PTY loop).
        // Button-3 lines after the 2-finger tap prove the injected
        // right-click is DELIVERED to a non-GTK4 client; button 4/5
        // lines after the 2-finger drag prove wheel delivery. The
        // legacy client's visible outcome is NOT proven here and stays
        // a disclosed trial limitation.
        arm("arm8", "legacy xev: button-3 + wheel DELIVERY proof (outcome unproven)") {
            val nd = ctx.applicationInfo.nativeLibraryDir
            val xevLog = File(ctx.filesDir, "guest-xev.log")
            xevLog.delete()
            val pb = ProcessBuilder(listOf(
                File(nd, "libproot.so").absolutePath, "--link2symlink", "-0",
                "-r", rootfs.absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", XServer.socketDir(ctx).absolutePath + ":/tmp/.X11-unix",
                "-w", "/root",
                "/usr/bin/xev", "-event", "button"))
            pb.environment().putAll(mapOf(
                "PROOT_LOADER" to File(nd, "libproot_loader.so").absolutePath,
                "PROOT_LOADER_32" to File(nd, "libproot_loader32.so").absolutePath,
                "LD_LIBRARY_PATH" to nd,
                "HOME" to "/root",
                "DISPLAY" to ":0",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "PROOT_TMP_DIR" to Proot.prootTmpDir(ctx).absolutePath,
                "TMPDIR" to "/tmp"))
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(xevLog))
            val xevProc = pb.start()
            Log.i(TAG, "harness: arm8 xev held process launched")
            try {
                var xv: NauWin? = null
                val xvDeadline = System.currentTimeMillis() + 45_000
                while (System.currentTimeMillis() < xvDeadline && xv == null) {
                    for (id in windowIds("Event Tester")) {
                        val g = winGeom(id)
                        if (g != null && g.w > 100 && g.h > 100) { xv = g; break }
                    }
                    if (xv == null) Thread.sleep(2_000)
                }
                Log.i(TAG, "harness: arm8 xev window=" + xv)
                if (xv != null) {
                    val xc = mapToDevice(xv.x + xv.w / 2, xv.y + xv.h / 2)
                    if (xc != null) {
                        tap(xc.first.toFloat(), xc.second.toFloat())  // focus + button-1 baseline
                        Thread.sleep(1_000)
                        twoFingerTap(xc.first.toFloat(), xc.second.toFloat(), "arm8")
                        Thread.sleep(1_500)
                        twoFingerDrag(xc.first.toFloat(), xc.second.toFloat(), 300f, "arm8")
                        Thread.sleep(1_500)
                        Shot.save("h-arm8-xev-after-gestures")
                    } else Log.i(TAG, "harness: arm8 no device mapping for xev")
                } else Log.i(TAG, "harness: arm8 xev window never appeared")
            } finally {
                xevProc.destroy()
                Thread.sleep(500)
                val txt = if (xevLog.isFile) xevLog.readText() else ""
                val presses = txt.split("ButtonPress event").drop(1)
                fun cnt(b: String) = presses.count { it.take(200).contains("button " + b + ",") }
                Log.i(TAG, "harness: arm8 xev DELIVERY button1=" + cnt("1") + " button3=" + cnt("3") +
                    " button4=" + cnt("4") + " button5=" + cnt("5") +
                    " (delivery ONLY; legacy visible outcome unproven = disclosed trial limitation)")
                Log.i(TAG, "harness: arm8 xev log tail: " + txt.takeLast(200).replace("\n", " | "))
            }
        }
        escClean()

        Log.i(TAG, "harness: HARNESS-EVIDENCE-ONLY - no gesture qualification asserted; " +
            "arm2=native-hold, arm3=2ftap deferred-fire+persistence, arm3b/3c=pending cancels, " +
            "arm4/arm5=drag/hold cancel-safety, arm6=GTK scroll real-content (pixel), " +
            "arm7=text selection (best-effort pixel), arm8=legacy xev DELIVERY-only; " +
            "LDOA-2FTAP-FIRE logcat deltas corroborate injection in arm3/arm8")
        } finally {
            // Owned-only teardown, best-effort, identity-revalidated through
            // the ledger: stop the session THIS test started via the product
            // calls (the product singleton trees are this test's here), then
            // record post-teardown survivors explicitly.
            try {
                Log.i(TAG, "harness: teardown begin (owned-only)")
                ledgerNote("pre-teardown gnome-launcher", proc)
                try { proc.destroy() } catch (t: Throwable) { Log.i(TAG, "harness: proc.destroy threw " + t) }
                try { XServer.stopDesktopMode(ctx) } catch (t: Throwable) { Log.i(TAG, "harness: stopDesktopMode threw " + t) }
                Thread.sleep(2_000)
                ledgerNote("post-teardown gnome-launcher", proc)
                Log.i(TAG, "harness: teardown end; survivor state in filesDir/harness-owned-ledger.txt")
            } catch (t: Throwable) {
                Log.i(TAG, "harness: teardown threw (survivors possible, see ledger): " + t)
            }
        }
    }
}
