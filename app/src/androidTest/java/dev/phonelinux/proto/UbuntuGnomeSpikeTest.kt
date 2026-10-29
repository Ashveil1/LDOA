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
        val proc = XServer.launchOrReuseGnomeSession(ctx, d, rootfs)

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
            xwin("xwininfo -root -tree | grep -i '" + pattern + "' | awk '{print ${'$'}1}'")
                .lines().filter { it.trimStart().startsWith("0x") }

        /** The VISIBLE nautilus window: (id, x, y) of the first matching
         *  window that IsViewable and >200x200. Nautilus owns hidden 1x1
         *  utility windows (run 40675364228: windowIds matched one at
         *  (0,0), the real Files window was elsewhere) and under Mutter
         *  the frame carries no "nautilus" name - the visible CSD window
         *  is found via its ("nautilus" "Nautilus") class in the full
         *  tree, so walk -tree and filter by map state + size. */
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

        fun atspi(): String = xwin(
            "export DBUS_SESSION_BUS_ADDRESS=\"${'$'}(cat /proc/[0-9]*/environ 2>/dev/null | tr '\\0' '\\n' | grep -m1 '^DBUS_SESSION_BUS_ADDRESS=' | cut -d= -f2-)\"; " +
                "timeout 30 python3 /root/atspi_dump.py 2>/dev/null | head -c 200000 || true")
        class ANode(val depth: Int, val role: String, val x: Int, val y: Int,
                    val w: Int, val h: Int, val name: String)
        fun parseAts(dump: String): List<ANode> = dump.lines().mapNotNull { ln ->
            val parts = ln.split("|")
            if (parts.size < 7) return@mapNotNull null
            ANode(parts[0].toIntOrNull() ?: return@mapNotNull null, parts[1],
                parts[2].toIntOrNull() ?: -1, parts[3].toIntOrNull() ?: -1,
                parts[4].toIntOrNull() ?: 0, parts[5].toIntOrNull() ?: 0, parts[6])
        }

        /** GTK CSD windows draw a large input-transparent drop shadow INSIDE
         *  the X window: run 40695193086 measured visible top-left at
         *  xwininfo pos + (63,55). Grabbing without the extents lands in the
         *  shadow zone and moves nothing. _GTK_FRAME_EXTENTS corrects it. */
        fun frameExtents(id: String): List<Int> {
            val out = xwin("xprop -id " + id + " _GTK_FRAME_EXTENTS _NET_FRAME_EXTENTS 2>/dev/null || true")
            val nums = Regex("-?\\d+").findAll(out.replace(Regex("_NET_FRAME_EXTENTS.*"), ""))
                .map { it.value.toInt() }.toList()
            Log.i(TAG, "dock-interact: frame extents for " + id + ": " + out.replace("\n", " | ").take(160))
            return if (nums.size >= 4) nums.take(4) else listOf(0, 0, 0, 0)
        }

        // Run 40925177246 (shot 21c): a modal "Pixel Launcher isn't
        // responding" ANR dialog was up at dock-tap time and the popup-clear
        // tap did not clear it; a modal dialog eats outside touches. Dismiss
        // it explicitly and VERIFY absence before any dock targeting.
        // Counters for the bounded launcher-ANR recovery below: dismissals
        // and the single recovery are counted and logged, never hidden.
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
        // storm (run 40953752639 spike: nexuslauncher ANR'd at 21:06 and
        // re-posted the dialog every ~6s through all 4 dismiss attempts,
        // defeating the point-in-time guard). Disposable emulator ONLY,
        // known nexuslauncher ANR ONLY, ONCE per test, loudly logged,
        // counts preserved. Recovery is VERIFIED: if the launcher does
        // not self-restart, the known component is started explicitly
        // (and logged); a 10s quiet window must stay clean. A persistent
        // storm after the single recovery fails the test as untreated -
        // no loop.
        fun anrEnvRecover(stage: String) {
            assertTrue("re-posted ANR on a non-emulator; environment recovery refused (untreated)",
                isDisposableEmulator())
            val attr = device.executeShellCommand(
                "dumpsys window windows | grep -m1 'Application Not Responding' || true").trim()
            assertTrue("re-posted ANR is not the known nexuslauncher case, refusing recovery: " + attr,
                attr.contains("com.google.android.apps.nexuslauncher"))
            anrRecoveryUsed = true
            Log.i(TAG, "ENVIRONMENT-RECOVERY-BEGIN[" + stage +
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
                Log.i(TAG, "ENVIRONMENT-RECOVERY[" + stage +
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
            Log.i(TAG, "ENVIRONMENT-RECOVERY-END[" + stage +
                "]: launcherPid=" + pid + " quietWindowClean=" + quiet)
            assertTrue("environment recovery failed (untreated ANR storm): launcherPid=" + pid +
                " quietWindowClean=" + quiet, pid.isNotEmpty() && quiet)
        }
        fun anrPresent(): Boolean = device.executeShellCommand(
            "dumpsys window windows | grep -m1 'Application Not Responding' || true"
        ).contains("Application Not Responding")
        fun dismissAnrVerified(stage: String) {
            var tries = 0
            while (anrPresent() && tries < 4) {
                tries++
                anrSeen++
                Log.i(TAG, "dock-interact: ANR dialog present, dismissing (attempt " + tries + ")")
                val waitBtn = device.findObject(androidx.test.uiautomator.UiSelector().text("Wait"))
                if (waitBtn != null && waitBtn.exists()) waitBtn.click() else device.pressBack()
                Thread.sleep(3_000)
            }
            // Dismissal is not proof the storm is over: run 40953752639
            // re-posted the dialog after EVERY attempt. A dialog still up
            // after the bounded dismissals is the storm case -> one
            // bounded recovery, never a dismiss loop.
            if (anrPresent() && !anrRecoveryUsed) {
                anrEnvRecover(stage)
            }
            assertTrue("ANR dialog still present after " + tries + " dismiss attempts" +
                (if (anrRecoveryUsed) " + one bounded environment recovery" else "") +
                " at " + stage + " (untreated)", !anrPresent())
        }
        dismissAnrVerified("dock-preconditions")

        // Dismiss the screen-lock notification banner (top center) so it
        // cannot cover the dock: swipe it upward off the stack.
        device.swipe(540, 200, 540, 60, 20)
        Thread.sleep(1_000)

        val beforeIds = windowIds("nautilus")
        Log.i(TAG, "dock-interact: nautilus before=" + beforeIds)
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
        dismissAnrVerified("dock-icon-tap")
        // Icon centers pixel-measured from the full-panel cold-start shot
        // (30, run 40671527573): Files = y~194 (grey/red cabinet),
        // second icon y~264, both spanning x~4-60 -> tap x=30.
        // X-to-device coordinate mapping: UNKNOWN a priori (run 40686393933
        // grabbed wallpaper - guest (216,111) was not the headerbar in
        // device px; Android status bar offsets the lorie surface and the
        // X screen size may differ from the panel). Probe it empirically:
        // park the finger at two device points, read the pointer in X
        // coords with xdotool, fit a linear map per axis.
        Log.i(TAG, "dock-interact: root dims: " + xwin("xwininfo -root | grep -E 'Width|Height|dimensions'").replace("\n", " | "))
        fun probeX(devX: Int, devY: Int): Pair<Int, Int>? {
            device.swipe(devX, devY, devX + 1, devY, 5)
            Thread.sleep(600)
            val out = xwin("xdotool getmouselocation --shell 2>/dev/null || true")
            val x = Regex("X=(-?\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
            val y = Regex("Y=(-?\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
            return if (x != null && y != null) Pair(x, y) else null
        }
        // A shown soft keyboard covers the lower surface and eats probe
        // swipes (5544785: post-fileops Gboard swallowed probe (700,1700),
        // degenerating the mapping to a (0,0) drag target that crashed
        // injection with RemoteException). Back hides the IME without
        // reaching the app only when it is actually shown.
        fun hideSoftKeyboardIfShown() {
            val dump = device.executeShellCommand("dumpsys input_method")
            if (dump.contains("mInputShown=true")) {
                device.pressBack()
                Thread.sleep(1_000)
            }
        }
        hideSoftKeyboardIfShown()
        var p1 = probeX(300, 1200)
        var p2 = probeX(700, 1700)
        Log.i(TAG, "dock-interact: mapping probes dev(300,1200)->" + p1 + " dev(700,1700)->" + p2)
        fun mapToDevice(x: Int, y: Int): Pair<Int, Int>? {
            val first = p1 ?: return null
            val second = p2 ?: return null
            // Identical/degenerate probes cannot define a mapping; a zero
            // divisor turns into NaN -> (0,0), never a usable target.
            if (Math.abs(second.first - first.first) < 10 ||
                Math.abs(second.second - first.second) < 10) return null
            val ax = (700 - 300).toDouble() / (second.first - first.first)
            val bx = 300 - ax * first.first
            val ay = (1700 - 1200).toDouble() / (second.second - first.second)
            val by = 1200 - ay * first.second
            return Pair((ax * x + bx).toInt(), (ay * y + by).toInt())
        }

        // Target the CURRENT dock geometry first: resolve the Files icon
        // from the live a11y tree (run 40925177246: icons sat near y130/175
        // on this panel while the taps hit y194/264 - stale per-panel
        // constants). Legacy candidates stay as logged fallbacks; the
        // gate itself (nautilus must open) is unchanged.
        val filesNode = parseAts(atspi()).firstOrNull {
            it.name.equals("Files", ignoreCase = true) && it.w >= 20 && it.h >= 20 && it.x <= 120
        }
        Log.i(TAG, "dock-interact: a11y Files node: " +
            (filesNode?.let { it.x.toString() + "," + it.y + " " + it.w + "x" + it.h } ?: "unresolved"))
        val tapCandidates = mutableListOf<Pair<Pair<Int, Int>, String>>()
        if (filesNode != null) {
            val c = mapToDevice(filesNode.x + filesNode.w / 2, filesNode.y + filesNode.h / 2)
            if (c != null) tapCandidates.add(c to "a11y-measured")
        }
        tapCandidates.add((30 to 194) to "legacy-194")
        tapCandidates.add((30 to 264) to "legacy-264")

        var nauWid: String? = null
        for ((attempt, cand) in tapCandidates.withIndex().map { it.index + 1 to it.value }) {
            val (tapPoint, tapLabel) = cand
            // Finger-like press: short hold instead of an instant
            // down/up, closer to how a finger lands on glass.
            device.swipe(tapPoint.first, tapPoint.second, tapPoint.first + 2, tapPoint.second, 25)
            Log.i(TAG, "dock-interact: pressed Files candidate " + tapLabel +
                " at (" + tapPoint.first + "," + tapPoint.second + ") attempt " + attempt)
            val openDeadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < openDeadline) {
                val vis = nauVisible()
                if (vis != null && vis.id !in beforeIds) { nauWid = vis.id; break }
                val proc = xwin("pgrep -x nautilus || true").trim()
                if (proc.isNotEmpty()) Log.i(TAG, "dock-interact: nautilus process alive pid=" + proc + " (no visible window yet)")
                Thread.sleep(3_000)
            }
            if (nauWid != null) break
        }
        Thread.sleep(10_000) // mapped geometry can precede the first painted frame
        Shot.save("22-ubuntu-dock-files-opened")
        assertTrue("finger press on the dock Files icon did not open a nautilus window (2 positions tried)", nauWid != null)

        val winBefore = nauVisible()
        val wBefore = winBefore?.let { Pair(it.x, it.y) }
        Log.i(TAG, "dock-interact: nautilus visible wid=" + nauWid + " geom=" + (winBefore?.let { it.x.toString() + "," + it.y + " " + it.w + "x" + it.h }))
        assertTrue("nautilus window geometry unreadable", winBefore != null)

        // Headerbar (CSD) drag: grab the RIGHT side of the visible
        // headerbar (empty stretch between the path bar and the view
        // buttons), corrected by _GTK_FRAME_EXTENTS so the finger lands on
        // the visible bar, not the input-transparent shadow margin.
        val fe = frameExtents(nauWid!!)
        val visX = winBefore!!.x + fe[0]
        val visY = winBefore.y + fe[2]
        val visW = winBefore.w - fe[0] - fe[1]
        val grabGuestX = visX + visW - 200
        val grabGuestY = visY + 20
        val grab = mapToDevice(grabGuestX, grabGuestY)
        assertTrue("X-to-device mapping unavailable for drag (probes=" + p1 + "," + p2 + ")", grab != null)
        Log.i(TAG, "dock-interact: drag grab guest(" + grabGuestX + "," + grabGuestY + ") vis=(" + visX + "," + visY + " w=" + visW + ") -> dev " + grab)
        // One continuous touchscreen gesture. Two UiDevice.swipe calls
        // lift the finger between hold and movement and cannot prove a
        // press-hold-drag. No retries: first-attempt failure stays red.
        fun fingerDrag(from: Pair<Int, Int>) {
            val down = android.os.SystemClock.uptimeMillis()
            fun event(action: Int, x: Float, y: Float) {
                // The short obtain() overload leaves toolType UNKNOWN.
                // Lorie dispatches touch only for TOOL_TYPE_FINGER, so set
                // both source AND pointer tool type, like UiDevice does.
                val props = android.view.MotionEvent.PointerProperties().apply {
                    id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
                }
                val coords = android.view.MotionEvent.PointerCoords().apply {
                    this.x = x; this.y = y; pressure = 1f; size = 1f
                }
                val e = android.view.MotionEvent.obtain(down,
                    android.os.SystemClock.uptimeMillis(), action, 1,
                    arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                    android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                check(e.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_FINGER)
                try { instr.sendPointerSync(e) } finally { e.recycle() }
            }
            fun dragDiag(phase: String) {
                // Ground the drag in X-level facts: where the server
                // thinks the pointer is and which window has focus.
                // Discriminates test-geometry misses from an input path
                // that never moved the pointer. Missing tools log as
                // such, never silently.
                Log.i(TAG, "dock-interact: drag-diag[" + phase + "] pointer: " +
                    xwin("(xdotool getmouselocation --shell 2>/dev/null || xinput --query-pointer 2>/dev/null || echo NO-POINTER-TOOL) | tr '\n' ' '").trim().take(140))
                Log.i(TAG, "dock-interact: drag-diag[" + phase + "] active window: " +
                    xwin("xdotool getactivewindow getwindowname 2>/dev/null || echo NA").trim().take(90))
            }
            dragDiag("pre-down")
            event(android.view.MotionEvent.ACTION_DOWN, from.first.toFloat(), from.second.toFloat())
            android.os.SystemClock.sleep(600)
            dragDiag("down")
            Shot.save("22d-ubuntu-drag-down")
            for (step in 1..30) {
                event(android.view.MotionEvent.ACTION_MOVE,
                    from.first - 60f * step / 30, from.second + 120f * step / 30)
                android.os.SystemClock.sleep(25)
                if (step == 15) {
                    dragDiag("mid")
                    Shot.save("22e-ubuntu-drag-mid")
                }
            }
            event(android.view.MotionEvent.ACTION_UP, from.first - 60f, from.second + 120f)
            dragDiag("up")
            Thread.sleep(8_000)
        }
        fingerDrag(grab!!)
        val wAfter = nauVisible()?.let { Pair(it.x, it.y) }
        Shot.save("23-ubuntu-window-dragged")
        Log.i(TAG, "dock-interact: continuous drag from=" + wBefore + " to=" + wAfter + " attempts=1")
        assertTrue("nautilus window did not follow finger drag (before=" + wBefore + " after=" + wAfter + ")",
            wAfter != null && wBefore != null &&
                Math.abs(wAfter.first - wBefore.first + 60) < 30 &&
                Math.abs(wAfter.second - wBefore.second - 120) < 30)
        Log.i(TAG, "UBUNTU-GNOME-INTERACTION-OK dock tap opened Files; continuous finger drag moved the window")

        // Real Files operations through the desktop UI, not guest mkdir.
        // Guest filesystem reads below verify the effects independently.
        // Modifier chords need REAL modifier key events: lorie forwards
        // each Android key event separately to the X server, so one
        // KeyEvent that only CARRIES meta state never presses Control/
        // Shift in the server and the chord degrades to a bare letter
        // (run 40803712185: Ctrl+Shift+N leaked 'n' into Nautilus
        // search-as-you-type instead of opening New Folder).
        val ua = instr.uiAutomation
        fun injectKey(action: Int, keyCode: Int, metaState: Int = 0) {
            val now = android.os.SystemClock.uptimeMillis()
            ua.injectInputEvent(android.view.KeyEvent(now, now, action, keyCode, 0, metaState), true)
        }
        fun chord(withShift: Boolean, keyCode: Int) {
            injectKey(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_CTRL_LEFT)
            if (withShift) injectKey(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_SHIFT_LEFT)
            val meta = android.view.KeyEvent.META_CTRL_ON or
                (if (withShift) android.view.KeyEvent.META_SHIFT_ON else 0)
            injectKey(android.view.KeyEvent.ACTION_DOWN, keyCode, meta)
            injectKey(android.view.KeyEvent.ACTION_UP, keyCode, meta)
            if (withShift) injectKey(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_SHIFT_LEFT)
            injectKey(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_CTRL_LEFT)
        }
        chord(true, android.view.KeyEvent.KEYCODE_N)
        Thread.sleep(3_000)
        // The dialog's default name may not be fully selected; force it.
        chord(false, android.view.KeyEvent.KEYCODE_A)
        device.executeShellCommand("input text LDOA-proof-created")
        device.pressEnter()
        Thread.sleep(5_000)
        Shot.save("23b-ubuntu-folder-created")
        assertTrue("Files create-folder UI did not create directory",
            File(rootfs, "root/LDOA-proof-created").isDirectory)
        // Newly created folder is selected in Nautilus.
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_F2)
        Thread.sleep(2_000)
        chord(false, android.view.KeyEvent.KEYCODE_A)
        device.executeShellCommand("input text LDOA-proof-renamed")
        device.pressEnter()
        Thread.sleep(5_000)
        Shot.save("23c-ubuntu-folder-renamed")
        assertTrue("Files rename UI did not rename directory",
            File(rootfs, "root/LDOA-proof-renamed").isDirectory &&
                !File(rootfs, "root/LDOA-proof-created").exists())
        Log.i(TAG, "UBUNTU-FILEOPS-OK UI folder create and rename independently verified")

        // --- Finger-gesture qualification, SIMULATED_TOUCH (touchMode=2,
        // forced every launch by MainActivity; the stock
        // SimulatedTouchInputStrategy owns the map here): tap = left click,
        // one-finger hold = BUTTON_LEFT press-hold (drag), two-finger tap =
        // right click, two-finger drag = wheel scroll. The direct-mode
        // (NullInputStrategy) LDOA patch paths - GTK press-and-hold and the
        // LDOA-2FTAP-FIRE two-finger-tap injection - are NOT active in this
        // mode; earlier arm proofs (runs 40851575540/40852753808) were
        // direct-mode and do not qualify mode 2. Runtime-
        // verified against the real desktop, never assumed from client docs:
        // menus are proven by their actual items and position (AT-SPI),
        // scroll by visible content movement plus the injected event stream
        // (xinput), toggles by behavior with them off and on again.
        // python3-pyatspi/xinput/xterm are test-only probes installed here,
        // not product packages.
        hideSoftKeyboardIfShown()

        // A modal system ANR dialog blocks input to the X surface (run
        // 40811548463: a Pixel Launcher ANR ate the Alpine maximize tap).
        // Bounded environment recovery: dismiss only allowlisted system
        // ANRs via the shared helper, log the count, assert the path is
        // clear; anything else stays up and fails the test.
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
            Log.i(TAG, "anr-guard[" + stage + "]: allowlisted dialogs dismissed=" + dismissed)
            // A dismissal is not proof the storm is over: the dialog
            // re-posts within seconds while the launcher stays hung. Watch
            // a bounded window after any dismissal; a re-post goes to the
            // single bounded recovery, not another silent dismiss cycle.
            if (dismissed > 0 && !anrRecoveryUsed) {
                val wEnd = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < wEnd) {
                    Thread.sleep(1_000)
                    if (anrDialogUp()) {
                        anrSeen++
                        Log.i(TAG, "anr-guard[" + stage + "]: ANR re-posted inside post-dismiss window (anrSeen=" + anrSeen + ")")
                        anrEnvRecover(stage)
                        break
                    }
                }
            }
            assertTrue("system ANR dialog still blocking input before " + stage, !anrDialogUp())
        }
        anrGuard("gesture-tests")

        Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
            "export DEBIAN_FRONTEND=noninteractive; apt-get install -y python3-pyatspi xinput xterm"),
            fakeRoot = true)
        File(rootfs, "root/atspi_dump.py").writeText("""
import pyatspi
def walk(node, depth):
    try: role = node.getRoleName()
    except Exception: return
    name = (node.name or '').replace('|', '/').replace('\n', ' ')
    try:
        e = node.queryComponent().getExtents(0)
        print('%d|%s|%d|%d|%d|%d|%s' % (depth, role, e.x, e.y, e.width, e.height, name))
    except Exception:
        print('%d|%s|-1|-1|0|0|%s' % (depth, role, name))
    try: n = node.childCount
    except Exception: n = 0
    for i in range(n):
        try: ch = node.getChildAtIndex(i)
        except Exception: continue
        if ch is not None: walk(ch, depth + 1)
d = pyatspi.Registry.getDesktop(0)
for i in range(d.childCount):
    a = d.getChildAtIndex(i)
    if a is not None: walk(a, 0)
""".trimIndent())
        // Only menu-tree nodes count: a file listing contains names like
        // "openssl" or "run-parts" that would otherwise fake menu items.
        fun menuHits(nodes: List<ANode>, probes: List<String>): List<String> =
            nodes.filter { n -> n.role.lowercase().contains("menu") &&
                probes.any { pr -> n.name.lowercase().contains(pr) } }
                .map { it.name }.distinct()
        fun rootChildIds(): Set<String> =
            xwin("xwininfo -root -children | awk '{print ${'$'}1}' | grep '^0x' || true")
                .split(Regex("\\s+")).filter { it.startsWith("0x") }.toSet()
        fun geomLine(id: String): String =
            xwin("xwininfo -id " + id + " | grep -E 'Absolute upper-left|Width:|Height:'").replace("\n", " | ")
        // Window-level menu proof, independent of the (slow, possibly
        // GTK4-blind) atspi dump: the set of new top-level X windows.
        fun plausibleMenuGeom(id: String): Boolean {
            val g = geomLine(id)
            val w = Regex("Width:\\s+(\\d+)").find(g)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val h = Regex("Height:\\s+(\\d+)").find(g)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            // Real context menus are at least tens of px in both axes; 1x1 /
            // origin-anchored windows are GNOME/mutter internals, not menus.
            return w >= 20 && h >= 20
        }
        fun awaitMenuWindow(beforeIds: Set<String>, timeoutMs: Long): Pair<List<String>, String>? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val added = (rootChildIds() - beforeIds).sorted()
                val plausible = added.filter { plausibleMenuGeom(it) }
                if (added.isNotEmpty() && plausible.isEmpty())
                    Log.i(TAG, "awaitMenuWindow: ignoring implausible new windows " + added +
                        " geom=" + geomLine(added.first()))
                if (plausible.isNotEmpty()) return Pair(plausible, geomLine(plausible.first()))
                Thread.sleep(700)
            }
            return null
        }
        fun awaitMenu(probes: List<String>, baseline: List<String>, timeoutMs: Long): List<ANode>? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val nodes = parseAts(atspi())
                if ((menuHits(nodes, probes) - baseline).size >= 2) return nodes
                Thread.sleep(700)
            }
            return null
        }
        fun awaitNoMenu(probes: List<String>, baseline: List<String>, timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if ((menuHits(parseAts(atspi()), probes) - baseline).isEmpty()) return true
                Thread.sleep(500)
            }
            return false
        }
        // Event-level probe of what the input layer injects into :0.
        fun probeStart(tag: String) {
            xwin("pkill -f 'xinput test-xi2' 2>/dev/null; rm -f /root/probe-" + tag + ".log; " +
                "(stdbuf -oL xinput test-xi2 --root > /root/probe-" + tag + ".log 2>&1 &)")
            Thread.sleep(2_000)
        }
        fun probeStop(tag: String): String {
            Thread.sleep(500)
            xwin("pkill -f 'xinput test-xi2' 2>/dev/null || true")
            Thread.sleep(500)
            return xwin("cat /root/probe-" + tag + ".log 2>/dev/null || true")
        }
        fun buttonPresses(probe: String, detail: Int): Int =
            Regex("ButtonPress\\)[\\s\\S]{0,200}?detail: " + detail).findAll(probe).count()
        fun fullShot(): android.graphics.Bitmap = instr.uiAutomation.takeScreenshot()
            ?: throw AssertionError("takeScreenshot returned null")
        fun regionDiff(a: android.graphics.Bitmap, b: android.graphics.Bitmap, r: android.graphics.Rect): Double {
            val x0 = r.left.coerceIn(0, a.width - 1); val x1 = r.right.coerceIn(0, a.width - 1)
            val y0 = r.top.coerceIn(0, a.height - 1); val y1 = r.bottom.coerceIn(0, a.height - 1)
            var diff = 0; var total = 0; var y = y0
            while (y <= y1) {
                var x = x0
                while (x <= x1) {
                    val pa = a.getPixel(x, y); val pb = b.getPixel(x, y)
                    if (Math.abs((pa and 0xff) - (pb and 0xff)) > 16 ||
                        Math.abs(((pa shr 8) and 0xff) - ((pb shr 8) and 0xff)) > 16 ||
                        Math.abs(((pa shr 16) and 0xff) - ((pb shr 16) and 0xff)) > 16) diff++
                    total++; x += 4
                }
                y += 4
            }
            return if (total == 0) 0.0 else diff.toDouble() / total
        }
        fun devRectOf(x: Int, y: Int, w: Int, h: Int): android.graphics.Rect {
            val tl = mapToDevice(x, y)!!; val br = mapToDevice(x + w, y + h)!!
            val dw = br.first - tl.first; val dh = br.second - tl.second
            return android.graphics.Rect(tl.first + dw / 50, tl.second + (dh * 0.12).toInt(),
                br.first - dw / 50, br.second - dh / 50)
        }
        fun guestRect(pattern: String): IntArray? {
            for (id in windowIds(pattern)) {
                val st = xwin("xwininfo -id " + id + " -stats")
                val map = Regex("Map State: (\\w+)").find(st)?.groupValues?.get(1)
                val w = Regex("Width: (\\d+)").find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val h = Regex("Height: (\\d+)").find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (map == "IsViewable" && w > 100 && h > 100) {
                    val x = Regex("Absolute upper-left X:\\s+(-?\\d+)").find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val y = Regex("Absolute upper-left Y:\\s+(-?\\d+)").find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    return intArrayOf(x, y, w, h)
                }
            }
            return null
        }

        fun longPress(at: Pair<Int, Int>, holdMs: Long = 800) {
            val down = android.os.SystemClock.uptimeMillis()
            val props = android.view.MotionEvent.PointerProperties().apply {
                id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            }
            val coords = android.view.MotionEvent.PointerCoords().apply {
                x = at.first.toFloat(); y = at.second.toFloat(); pressure = 1f; size = 1f
            }
            fun ev(action: Int) = android.view.MotionEvent.obtain(down,
                android.os.SystemClock.uptimeMillis(), action, 1,
                arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            var e = ev(android.view.MotionEvent.ACTION_DOWN)
            try { instr.sendPointerSync(e) } finally { e.recycle() }
            android.os.SystemClock.sleep(holdMs)
            e = ev(android.view.MotionEvent.ACTION_UP)
            try { instr.sendPointerSync(e) } finally { e.recycle() }
        }
        fun holdDrag(from: Pair<Int, Int>, dx: Int, dy: Int, holdMs: Long) {
            val down = android.os.SystemClock.uptimeMillis()
            val props = android.view.MotionEvent.PointerProperties().apply {
                id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            }
            val coords = android.view.MotionEvent.PointerCoords().apply {
                x = from.first.toFloat(); y = from.second.toFloat(); pressure = 1f; size = 1f
            }
            fun ev(action: Int) = android.view.MotionEvent.obtain(down,
                android.os.SystemClock.uptimeMillis(), action, 1,
                arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            var e = ev(android.view.MotionEvent.ACTION_DOWN)
            try { instr.sendPointerSync(e) } finally { e.recycle() }
            android.os.SystemClock.sleep(holdMs)
            val steps = 12
            for (i in 1..steps) {
                coords.x = from.first + dx * i / steps.toFloat()
                coords.y = from.second + dy * i / steps.toFloat()
                e = ev(android.view.MotionEvent.ACTION_MOVE)
                try { instr.sendPointerSync(e) } finally { e.recycle() }
                android.os.SystemClock.sleep(30)
            }
            e = ev(android.view.MotionEvent.ACTION_UP)
            try { instr.sendPointerSync(e) } finally { e.recycle() }
        }

        fun twoFingerSwipe(cx1: Int, cy1: Int, cx2: Int, cy2: Int, dx: Int, dy: Int) {
            val down = android.os.SystemClock.uptimeMillis()
            val p1 = android.view.MotionEvent.PointerProperties().apply {
                id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            }
            val p2 = android.view.MotionEvent.PointerProperties().apply {
                id = 1; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            }
            fun c(x: Float, y: Float) = android.view.MotionEvent.PointerCoords().apply {
                this.x = x; this.y = y; pressure = 1f; size = 1f
            }
            fun send(action: Int, pts: Array<android.view.MotionEvent.PointerProperties>,
                     cx1: Float, cy1: Float, cx2: Float, cy2: Float) {
                val coords = if (pts.size == 1) arrayOf(c(cx1, cy1)) else arrayOf(c(cx1, cy1), c(cx2, cy2))
                val e = android.view.MotionEvent.obtain(down,
                    android.os.SystemClock.uptimeMillis(), action, pts.size, pts, coords,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { instr.sendPointerSync(e) } finally { e.recycle() }
            }
            val idx1 = 1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT
            send(android.view.MotionEvent.ACTION_DOWN, arrayOf(p1), cx1.toFloat(), cy1.toFloat(), 0f, 0f)
            send(android.view.MotionEvent.ACTION_POINTER_DOWN or idx1, arrayOf(p1, p2),
                cx1.toFloat(), cy1.toFloat(), cx2.toFloat(), cy2.toFloat())
            val steps = 12
            for (st in 1..steps) {
                send(android.view.MotionEvent.ACTION_MOVE, arrayOf(p1, p2),
                    cx1 + dx * st / steps.toFloat(), cy1 + dy * st / steps.toFloat(),
                    cx2 + dx * st / steps.toFloat(), cy2 + dy * st / steps.toFloat())
                android.os.SystemClock.sleep(40)
            }
            send(android.view.MotionEvent.ACTION_POINTER_UP or idx1, arrayOf(p1, p2),
                (cx1 + dx).toFloat(), (cy1 + dy).toFloat(), (cx2 + dx).toFloat(), (cy2 + dy).toFloat())
            send(android.view.MotionEvent.ACTION_UP, arrayOf(p1), (cx1 + dx).toFloat(), (cy1 + dy).toFloat(), 0f, 0f)
        }

        // Clean two-finger tap (no MOVE: movement cancels the tap). In
        // SIMULATED_TOUCH (touchMode=2, forced every launch by
        // MainActivity) the stock SimulatedTouchInputStrategy maps a
        // two-finger tap to onTap(BUTTON_RIGHT) -> one injected
        // right-click; the LDOA-2FTAP-FIRE patch path is gated on the
        // DIRECT (NullInputStrategy) mode and never runs here.
        fun twoFingerTap(cx1: Int, cy1: Int, cx2: Int, cy2: Int) {
            val down = android.os.SystemClock.uptimeMillis()
            val p1 = android.view.MotionEvent.PointerProperties().apply {
                id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            }
            val p2 = android.view.MotionEvent.PointerProperties().apply {
                id = 1; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            }
            fun c(x: Float, y: Float) = android.view.MotionEvent.PointerCoords().apply {
                this.x = x; this.y = y; pressure = 1f; size = 1f
            }
            fun send(action: Int, pts: Array<android.view.MotionEvent.PointerProperties>,
                     ax: Float, ay: Float, bx: Float, by: Float) {
                val coords = if (pts.size == 1) arrayOf(c(ax, ay)) else arrayOf(c(ax, ay), c(bx, by))
                val e = android.view.MotionEvent.obtain(down,
                    android.os.SystemClock.uptimeMillis(), action, pts.size, pts, coords,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { instr.sendPointerSync(e) } finally { e.recycle() }
            }
            val idx1 = 1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT
            send(android.view.MotionEvent.ACTION_DOWN, arrayOf(p1), cx1.toFloat(), cy1.toFloat(), 0f, 0f)
            send(android.view.MotionEvent.ACTION_POINTER_DOWN or idx1, arrayOf(p1, p2),
                cx1.toFloat(), cy1.toFloat(), cx2.toFloat(), cy2.toFloat())
            android.os.SystemClock.sleep(150)
            send(android.view.MotionEvent.ACTION_POINTER_UP or idx1, arrayOf(p1, p2),
                cx1.toFloat(), cy1.toFloat(), cx2.toFloat(), cy2.toFloat())
            send(android.view.MotionEvent.ACTION_UP, arrayOf(p1), cx1.toFloat(), cy1.toFloat(), 0f, 0f)
        }

        val nwg = nauVisible()
        assertTrue("Files window missing before gesture tests", nwg != null)
        val nwgP = nwg!!
        val fe0 = frameExtents(nwgP.id)
        val bgGuestX = nwgP.x + fe0[0] + (nwgP.w - fe0[0] - fe0[1]) / 2
        val bgGuestY = nwgP.y + fe0[2] + (nwgP.h - fe0[2] - fe0[3]) - 80
        val bgTarget = mapToDevice(bgGuestX, bgGuestY)
        assertTrue("no device mapping for long-press target", bgTarget != null)

        // (a) TWO-FINGER TAP = RIGHT-CLICK, surface 1: Files background
        // (SIMULATED_TOUCH contract). The proof is the menu itself: real
        // Nautilus menu items appear near the tap point that were not there
        // before, forming exactly one contiguous menu in exactly one new
        // menu window, opened by exactly one injected pointer right-click
        // on :0. Gated on actual button-3 delivery + menu, never on a
        // patch-marker log tag.
        val bgProbes = listOf("new folder", "paste", "select all", "properties",
            "open in terminal", "sort", "undo", "redo", "restore", "empty trash")
        val baselineBgNames = menuHits(parseAts(atspi()), bgProbes)
        Log.i(TAG, "2ftap-bg: baseline item names=" + baselineBgNames)
        val menuBeforeIds = rootChildIds()
        Log.i(TAG, "2ftap-bg: effective touchMode pref=" +
            ctx.getSharedPreferences(ctx.packageName + "_preferences",
                android.content.Context.MODE_PRIVATE).getString("touchMode", null) +
            " (2 = SIMULATED_TOUCH; right-click via stock onTap(BUTTON_RIGHT), no LDOA-2FTAP-FIRE in this mode)")
        probeStart("menu")
        twoFingerTap(bgTarget!!.first - 40, bgTarget!!.second, bgTarget!!.first + 40, bgTarget!!.second)
        // Fast window-level signal first (the atspi dump can take 10-30s
        // per call, so a short atspi-only wait misses a slow dump).
        val menuWin = awaitMenuWindow(menuBeforeIds, 15_000)
        val menuProbe = probeStop("menu")
        Shot.save("23d-ubuntu-2ftap-menu")
        val menuNodes = awaitMenu(bgProbes, baselineBgNames, 35_000)
        val injected3 = buttonPresses(menuProbe, 3)
        Log.i(TAG, "2ftap-bg: menuWin=" + menuWin + " rightClickInjections=" +
            injected3 + " probeBytes=" + menuProbe.length +
            " probeTail=" + menuProbe.takeLast(300).replace("\n", " | ") +
            " atspiNull=" + (menuNodes == null))
        if (menuWin == null || injected3 != 1) {
            // DIAGNOSTIC ONLY (never asserted): is the X input stack able to
            // open a menu at that point at all? xdotool uses XTEST, an
            // independent path from the lorie mouse injection. If XTEST
            // opens a menu, the lorie mouse path is inert; if not, the
            // client/point refuses right-click menus. The XTEST click is
            // wrapped in its own probe window as a POSITIVE CONTROL: if the
            // probe records the XTEST button-3, an earlier zero count for
            // the product injection is a real negative; if it does not, the
            // probe itself is unhealthy and zero counts are void. Runs on
            // the original failure; the original asserts below still decide
            // the verdict unchanged.
            val xlist = xwin("xinput list 2>/dev/null | head -24 || true").replace("\n", " | ")
            val mloc = xwin("xdotool getmouselocation 2>/dev/null || true").replace("\n", " | ")
            Log.i(TAG, "2ftap-bg DIAG: xinput list=" + xlist)
            Log.i(TAG, "2ftap-bg DIAG: mouse=" + mloc + " probeBytes=" + menuProbe.length +
                " probeHead=" + menuProbe.take(300).replace("\n", " | "))
            probeStart("hc")
            xwin("xdotool mousemove " + bgGuestX + " " + bgGuestY + " click 3 2>/dev/null || true")
            Thread.sleep(3_000)
            val hcProbe = probeStop("hc")
            Log.i(TAG, "2ftap-bg DIAG: health-check probe button3=" + buttonPresses(hcProbe, 3) +
                " motion=" + hcProbe.contains("Motion") + " hcBytes=" + hcProbe.length)
            val xdWin = (rootChildIds() - menuBeforeIds).sorted()
            Log.i(TAG, "2ftap-bg DIAG: xdotool right-click at the same point -> new windows=" + xdWin +
                " plausible=" + xdWin.filter { plausibleMenuGeom(it) })
            if (xdWin.any { plausibleMenuGeom(it) }) {
                Shot.save("23d2b-xdotool-menu")
                device.pressKeyCode(android.view.KeyEvent.KEYCODE_ESCAPE)
                Thread.sleep(1_000)
            }
        }
        assertTrue("two-finger tap on Files background produced no menu window (SIMULATED_TOUCH) " +
            "(atspi items=" + (menuNodes?.let { menuHits(it, bgProbes) - baselineBgNames } ?: "dump-pending") +
            ", probeTail=" + menuProbe.takeLast(300).replace("\n", " | ") + ")",
            menuWin != null || menuNodes != null)
        assertTrue("expected exactly one new menu window, added=" + (menuWin?.first ?: emptyList<String>()) +
            " (native+injected duplicate?)", menuWin != null && menuWin.first.size == 1)
        assertTrue("expected exactly one injected button-3 press, got " + injected3,
            injected3 == 1)
        val mwX = Regex("Absolute upper-left X:\\s+(-?\\d+)").find(menuWin!!.second)?.groupValues?.get(1)?.toIntOrNull() ?: -10000
        val mwY = Regex("Absolute upper-left Y:\\s+(-?\\d+)").find(menuWin.second)?.groupValues?.get(1)?.toIntOrNull() ?: -10000
        assertTrue("menu window opened far from the two-finger tap point (menu at " + mwX + "," + mwY +
            " vs tap " + bgGuestX + "," + bgGuestY + ")",
            Math.abs(mwX - bgGuestX) <= 400 && Math.abs(mwY - bgGuestY) <= 400)
        assertTrue("menu opened but atspi saw no items - GTK4 a11y blindness " +
            "or a truncated dump; item names are required proof", menuNodes != null)
        val bgNew = menuHits(menuNodes!!, bgProbes) - baselineBgNames
        val hitIdx = menuNodes.mapIndexedNotNull { idx, n -> if (n.name in bgNew) idx else null }
        val spread = if (hitIdx.isEmpty()) -1 else hitIdx.last() - hitIdx.first()
        Log.i(TAG, "2ftap-bg: new menu items=" + bgNew + " idxSpread=" + spread)
        assertTrue("context menu items not identifiable (found=" + bgNew + ")", bgNew.size >= 2)
        assertTrue("menu items not contiguous - more than one menu? (spread=" + spread + ")",
            spread in 0..(bgNew.size * 4))
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_ESCAPE)
        assertTrue("context menu did not close on Escape", awaitNoMenu(bgProbes, baselineBgNames, 4_000))
        Log.i(TAG, "UBUNTU-2FTAP-RIGHTCLICK-OK Files background menu: " + bgNew)

        // (b) No spurious right-click: a one-finger drag and a plain tap on
        // the desktop background (outside every window) must open nothing.
        val geom = xwin("xdotool getdisplaygeometry").trim().split(Regex("\\s+"))
        val scrW = geom.getOrNull(0)?.toIntOrNull() ?: 1080
        val scrH = geom.getOrNull(1)?.toIntOrNull() ?: 2160
        val deskTarget = mapToDevice(scrW - 80, scrH - 60)
        assertTrue("no mapping for desktop point", deskTarget != null)
        val deskP = deskTarget!!
        device.swipe(deskP.first - 60, deskP.second - 30, deskP.first, deskP.second, 20)
        Thread.sleep(1_200)
        assertTrue("one-finger drag spuriously opened a context menu",
            (menuHits(parseAts(atspi()), bgProbes) - baselineBgNames).isEmpty())
        device.click(deskP.first, deskP.second)
        Thread.sleep(1_200)
        assertTrue("plain tap spuriously opened a context menu",
            (menuHits(parseAts(atspi()), bgProbes) - baselineBgNames).isEmpty())
        // A short 400ms hold (below the Android long-press timeout) is a
        // plain left click in SIMULATED_TOUCH and must not right-click.
        // A 1-finger hold past the timeout is a BUTTON_LEFT press-hold
        // (drag) in this mode and never injects button-3 either - these
        // stay as regression guards. A two-finger drag must stay a wheel
        // scroll, not a right-click.
        probeStart("shorthold")
        longPress(deskP, 400)
        Thread.sleep(600)
        val shProbe = probeStop("shorthold")
        assertTrue("sub-threshold hold injected button-3 (probeTail=" +
            shProbe.takeLast(200).replace("\n", " | ") + ")", buttonPresses(shProbe, 3) == 0)
        assertTrue("sub-threshold hold opened a menu",
            (menuHits(parseAts(atspi()), bgProbes) - baselineBgNames).isEmpty())
        probeStart("twofingbg")
        twoFingerSwipe(deskP.first - 40, deskP.second - 80, deskP.first + 40, deskP.second - 80, 0, 120)
        Thread.sleep(600)
        val tfProbe = probeStop("twofingbg")
        assertTrue("two-finger gesture injected button-3", buttonPresses(tfProbe, 3) == 0)
        assertTrue("two-finger gesture opened a menu",
            (menuHits(parseAts(atspi()), bgProbes) - baselineBgNames).isEmpty())
        Log.i(TAG, "UBUNTU-HOLD-THRESHOLD-OK 400ms hold and two-finger drag caused no right-click")
        Log.i(TAG, "UBUNTU-NO-SPURIOUS-RIGHTCLICK-OK drag and tap stayed drag/click")

        // (c) TWO-FINGER TAP = RIGHT-CLICK, surface 2: a file row (the
        // folder created and renamed by the fileops steps). Fallback if the
        // row is not in the a11y tree: the window headerbar's own menu
        // (window count). SIMULATED_TOUCH: a 1-finger hold here would be a
        // left press-hold, so the right-click trigger is the 2-finger tap.
        val rowNode = parseAts(atspi()).firstOrNull { it.name == "LDOA-proof-renamed" && it.w > 0 }
        if (rowNode != null) {
            val rgX = rowNode.x + rowNode.w / 2; val rgY = rowNode.y + rowNode.h / 2
            val rowTarget = mapToDevice(rgX, rgY)
            assertTrue("no device mapping for file-row hold", rowTarget != null)
            val rowProbes = listOf("open", "cut", "copy", "rename", "move to trash",
                "star", "properties", "compress", "run")
            val baselineRow = menuHits(parseAts(atspi()), rowProbes)
            twoFingerTap(rowTarget!!.first - 40, rowTarget!!.second, rowTarget!!.first + 40, rowTarget!!.second)
            val rowMenu = awaitMenu(rowProbes, baselineRow, 35_000)
            Shot.save("23d2-ubuntu-2ftap-row-menu")
            assertTrue("two-finger tap on a file row produced no context menu", rowMenu != null)
            val rowNew = menuHits(rowMenu!!, rowProbes) - baselineRow
            Log.i(TAG, "2ftap-row: new items=" + rowNew)
            assertTrue("row context menu items not identifiable (found=" + rowNew + ")", rowNew.size >= 2)
            val posR = rowMenu.firstOrNull { it.name in rowNew && it.w > 0 }
            assertTrue("row menu opened far from the two-finger tap point", posR != null &&
                Math.abs(posR.x + posR.w / 2 - rgX) <= 400 && Math.abs(posR.y + posR.h / 2 - rgY) <= 400)
            device.pressKeyCode(android.view.KeyEvent.KEYCODE_ESCAPE)
            assertTrue("row menu did not close on Escape", awaitNoMenu(rowProbes, baselineRow, 4_000))
            Log.i(TAG, "UBUNTU-2FTAP-RIGHTCLICK-SURFACE2-OK file row menu: " + rowNew)
        } else {
            Log.i(TAG, "2ftap-row: folder node not in a11y tree; headerbar fallback")
            val before = xwin("xwininfo -root -children | grep -c '0x' || true").trim().toIntOrNull() ?: 0
            val hb = mapToDevice(nwgP.x + fe0[0] + (nwgP.w - fe0[0] - fe0[1]) - 200, nwgP.y + fe0[2] + 20)
            assertTrue("headerbar fallback mapping missing", hb != null)
            twoFingerTap(hb!!.first - 40, hb!!.second, hb!!.first + 40, hb!!.second)
            Thread.sleep(2_500)
            val after = xwin("xwininfo -root -children | grep -c '0x' || true").trim().toIntOrNull() ?: 0
            Shot.save("23d2-ubuntu-2ftap-headerbar-menu")
            assertTrue("two-finger tap on the headerbar opened no window menu (" + before + " -> " + after + ")", after > before)
            device.pressKeyCode(android.view.KeyEvent.KEYCODE_ESCAPE)
            Thread.sleep(1_000)
            Log.i(TAG, "UBUNTU-2FTAP-RIGHTCLICK-SURFACE2-OK headerbar menu (windows " + before + " -> " + after + ")")
        }

        // (d) TWO-FINGER DRAG = WHEEL SCROLL on modern content: Files at
        // /usr/bin. SIMULATED_TOUCH delivers this as discrete wheel BUTTON
        // 4/5 events (SimulatedTouchInputStrategy.onScroll ->
        // sendMouseWheelEvent); the smooth-valuator stream is the direct-
        // mode path and is not expected here. Proof is the actual wheel
        // delivery AND visible content movement in the window interior.
        chord(false, android.view.KeyEvent.KEYCODE_L)
        Thread.sleep(1_500)
        device.executeShellCommand("input text /usr/bin")
        device.pressEnter()
        Thread.sleep(6_000)
        anrGuard("scroll")
        val nws = nauVisible()!!
        val scrollRect = devRectOf(nws.x, nws.y, nws.w, nws.h)
        val sc = mapToDevice(nws.x + nws.w / 2, nws.y + nws.h / 2)
        assertTrue("no device mapping for scroll center", sc != null)
        val scP = sc!!
        val bmpBefore = fullShot()
        Shot.save("23e-ubuntu-scroll-target")
        probeStart("scroll")
        twoFingerSwipe(scP.first - 60, scP.second, scP.first + 60, scP.second, 0, -200)
        Thread.sleep(900)
        twoFingerSwipe(scP.first - 60, scP.second, scP.first + 60, scP.second, 0, -200)
        val scrollProbe = probeStop("scroll")
        val bmpAfter = fullShot()
        Shot.save("23f-ubuntu-twofinger-scrolled")
        val wheelUp = buttonPresses(scrollProbe, 4)
        val wheelDown = buttonPresses(scrollProbe, 5)
        val smoothEvents = Regex("valuators:[^\\n]*3: -?\\d").findAll(scrollProbe).count()
        val movedFrac = regionDiff(bmpBefore, bmpAfter, scrollRect)
        Log.i(TAG, "twofinger-scroll: wheelBtn4=" + wheelUp + " wheelBtn5=" + wheelDown +
            " smoothValuatorEvents=" + smoothEvents + " (smooth is direct-mode only)" +
            " visibleChangeFraction=" + movedFrac +
            " probeTail=" + scrollProbe.takeLast(400).replace("\n", " | "))
        assertTrue("two-finger drag delivered no wheel-button events to :0 (SIMULATED_TOUCH onScroll; probeTail=" +
            scrollProbe.takeLast(300).replace("\n", " | ") + ")", wheelUp + wheelDown > 0)
        assertTrue("Files content did not visibly move after two-finger drags (diff=" + movedFrac + ")",
            movedFrac > 0.05)
        Log.i(TAG, "UBUNTU-TWOFINGER-SCROLL-OK injected scroll and visible movement (" + movedFrac + ")")

        // (e) Legacy client coverage: xterm has no XI2 smooth scrolling;
        // it scrolls only if the server converts the valuators to button
        // 4/5 steps (SetScrollValuator). Proof is visible scrollback
        // movement; the probe records any emulated button events.
        xwin("(xterm -sb -geometry 100x30+200+250 -e /bin/sh -c 'seq 1 400; sleep 240' >/dev/null 2>&1 &) ; sleep 2")
        var xt: IntArray? = null
        val xtDeadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < xtDeadline) {
            xt = guestRect("xterm")
            if (xt != null) break
            Thread.sleep(1_000)
        }
        assertTrue("xterm window never mapped", xt != null)
        val xtP = xt!!
        val xrect = devRectOf(xtP[0], xtP[1], xtP[2], xtP[3])
        val xc = mapToDevice(xtP[0] + xtP[2] / 2, xtP[1] + xtP[3] / 2)!!
        val bmpX0 = fullShot()
        Shot.save("23g-ubuntu-xterm-before-scroll")
        probeStart("xterm")
        // Finger-DOWN swipe: view toward earlier lines (scroll back).
        twoFingerSwipe(xc.first - 60, xc.second, xc.first + 60, xc.second, 0, 200)
        Thread.sleep(900)
        twoFingerSwipe(xc.first - 60, xc.second, xc.first + 60, xc.second, 0, 200)
        val xtermProbe = probeStop("xterm")
        val bmpX1 = fullShot()
        Shot.save("23h-ubuntu-xterm-scrolled")
        val xMoved = regionDiff(bmpX0, bmpX1, xrect)
        val legacyUp = buttonPresses(xtermProbe, 4)
        Log.i(TAG, "xterm-scroll: visibleChangeFraction=" + xMoved + " emulatedBtn4=" + legacyUp +
            " probeTail=" + xtermProbe.takeLast(400).replace("\n", " | "))
        assertTrue("xterm content did not visibly move (legacy scroll path broken, diff=" + xMoved + ")",
            xMoved > 0.05)
        Log.i(TAG, "UBUNTU-LEGACY-SCROLL-OK xterm scrolled visibly (" + xMoved + ")")
        xwin("xwininfo -root -tree | grep -i xterm | awk '{print \$1}' | head -1 | xargs -r xkill -id || true")

        // (a3) HOLD-THEN-DRAG regression: a press that pauses past the
        // right-click threshold and then drags the Files titlebar must stay
        // a native drag - the window follows and no right-click fires.
        // (007bf07 broke this via the onLongPress takeover.)
        val hbNau = nauVisible()
        if (hbNau != null) {
            val feH = frameExtents(hbNau.id)
            val hbGX = hbNau.x + feH[0] + (hbNau.w - feH[0] - feH[1]) / 2
            val hbGY = hbNau.y + feH[2] + 12
            val hbD = mapToDevice(hbGX, hbGY)
            assertTrue("no mapping for hold-drag titlebar point", hbD != null)
            val posBefore = xwin("xwininfo -id " + hbNau.id + " | grep Absolute")
            probeStart("holddrag")
            holdDrag(hbD!!, 150, 80, 700)
            Thread.sleep(1_000)
            val hdProbe = probeStop("holddrag")
            val posAfter = xwin("xwininfo -id " + hbNau.id + " | grep Absolute")
            Shot.save("23h-ubuntu-hold-drag")
            val bx = Regex("Absolute upper-left X:\\s+(-?\\d+)").find(posBefore)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val by = Regex("Absolute upper-left Y:\\s+(-?\\d+)").find(posBefore)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val ax = Regex("Absolute upper-left X:\\s+(-?\\d+)").find(posAfter)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val ay = Regex("Absolute upper-left Y:\\s+(-?\\d+)").find(posAfter)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            Log.i(TAG, "hold-drag: before=(" + bx + ", " + by + ") after=(" + ax + ", " + ay +
                ") button3=" + buttonPresses(hdProbe, 3))
            assertTrue("hold-then-drag injected a right-click", buttonPresses(hdProbe, 3) == 0)
            assertTrue("hold-then-drag opened a menu",
                (menuHits(parseAts(atspi()), bgProbes) - baselineBgNames).isEmpty())
            assertTrue("window did not follow hold-then-drag (before=(" + bx + "," + by +
                ") after=(" + ax + "," + ay + "))",
                Math.abs(ax - bx - 150) <= 60 && Math.abs(ay - by - 80) <= 60)
            Log.i(TAG, "UBUNTU-HOLD-DRAG-OK window followed finger: (" + bx + "," + by + ")->(" + ax + "," + ay + ")")
        } else {
            Log.i(TAG, "hold-drag: Files window not visible; skipped")
        }
        Thread.sleep(1_000)

        // (f) TOGGLES IN SIMULATED_TOUCH: NONE of the product toggles have
        // a mode-2 reader - both pref consumers (ldoa_twofinger_tap_rightclick
        // TouchInputHandler.java:486, ldoa_twofinger_scroll :815) are gated
        // on the direct (NullInputStrategy) branch. In this mode the stock
        // two-finger tap right-click and wheel scroll fire regardless of the
        // switches, so there is no meaningful toggle behavior to GATE. What
        // remains is EVIDENCE for the pending UI-cleanup decision: flip both
        // off, deliver a two-finger tap, and record whether delivery
        // continues (toggle inert in mode 2), then restore defaults.
        val prefs = ctx.getSharedPreferences(ctx.packageName + "_preferences",
            android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("ldoa_twofinger_tap_rightclick", false)
            .putBoolean("ldoa_twofinger_scroll", false).apply()
        Thread.sleep(500)
        probeStart("offtap")
        twoFingerTap(bgTarget!!.first - 40, bgTarget!!.second, bgTarget!!.first + 40, bgTarget!!.second)
        Thread.sleep(2_000)
        val offTapProbe = probeStop("offtap")
        val offTapInjected = buttonPresses(offTapProbe, 3)
        val offMenuIds = (rootChildIds() - menuBeforeIds).sorted()
        Log.i(TAG, "toggles-off 2ftap: injectedRightClicks=" + offTapInjected +
            " newWindows=" + offMenuIds +
            " (NONZERO here proves the toggle is INERT in SIMULATED_TOUCH - both readers are direct-mode-gated)")
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_ESCAPE)
        Thread.sleep(800)
        prefs.edit().putBoolean("ldoa_twofinger_tap_rightclick", true)
            .putBoolean("ldoa_twofinger_scroll", true).apply()
        Thread.sleep(500)
        Shot.save("23i-ubuntu-toggles-off")
        Log.i(TAG, "UBUNTU-TOGGLES-MODE2-EVIDENCE toggles flipped off/on; delivery line above records inertness")

        // Kill a pid with all its /proc descendants (deepest first): guest
        // binaries exec under proot so their cmdline does not carry the
        // rootfs path - name/pattern kills miss them (run 40702734816 left
        // gnome-session alive, relaunch never owned :0).
        fun killProcessTree(pid: Int) {
            val kids = HashMap<Int, MutableList<Int>>()
            java.io.File("/proc").listFiles()?.forEach { dir ->
                val cpid = dir.name.toIntOrNull() ?: return@forEach
                try {
                    val stat = java.io.File(dir, "stat").readText()
                    val ppid = stat.substringAfterLast(')').trim().split(Regex("\\s+"))[1].toInt()
                    kids.getOrPut(ppid) { mutableListOf() }.add(cpid)
                } catch (_: Throwable) { }
            }
            fun rec(p: Int) { kids[p]?.forEach { rec(it) }; android.os.Process.killProcess(p) }
            rec(pid)
        }

        // 4. RESTART persistence: kill the whole session, relaunch, the
        // desktop must come back (shell windows reappear on :0).
        // Kill the full process TREE from the Android side via the Java
        // handle: run 40702734816's in-guest /proc scan killed gnome-shell
        // but left gnome-session alive, so the old session kept :0 and the
        // relaunch never owned the display.
        // The proot PARENTS carry the rootfs path in their cmdline (guest
        // children do not): find them, kill their whole trees.
        val oldShellPids = xwin("pgrep -x gnome-shell || true").trim().lines().filter { it.toIntOrNull() != null }.toSet()
        Log.i(TAG, "restart: pre-kill shell pids=" + oldShellPids)
        var killedRoots = 0
        java.io.File("/proc").listFiles()?.forEach { dir ->
            val cpid = dir.name.toIntOrNull() ?: return@forEach
            try {
                val cmd = java.io.File(dir, "cmdline").readText()
                if (cmd.contains("distros/ubuntu") && !cmd.contains("pkill")) {
                    killProcessTree(cpid); killedRoots++
                }
            } catch (_: Throwable) { }
        }
        Log.i(TAG, "restart: killed " + killedRoots + " session root(s)")
        val deathDeadline = System.currentTimeMillis() + 60_000
        while (proc.isAlive && System.currentTimeMillis() < deathDeadline) Thread.sleep(2_000)
        Log.i(TAG, "restart: old session alive=" + proc.isAlive)
        assertTrue("old gnome session process tree survived the kill", !proc.isAlive)
        fun mirrorGuestLog() {
            if (logFile.isFile) logFile.readText().chunked(900).forEachIndexed { i, c ->
                Log.i(TAG, "restart-full[$i]: $c")
            }
        }
        // Bind the X window manager's identity to its actual process.
        // xwininfo names the shell 'GNOME Shell', not 'gnome-shell'. A
        // case-insensitive grep alone cannot establish a live manager.
        var lastIdentityEvidence = ""
        fun shellIdentity(): Boolean {
            val wm = xwin("xprop -root _NET_SUPPORTING_WM_CHECK")
            val id = Regex("0x[0-9a-fA-F]+").find(wm)?.value
            val props = if (id != null) xwin("xprop -id " + id + " _NET_WM_NAME _NET_WM_PID WM_NAME") else "<no WM id>"
            // Mutter does not publish _NET_WM_PID on this support window
            // (5ce37e3). Require the named supporting window AND a new live
            // exact-name shell process, excluding every pre-kill PID.
            val newPids = xwin("pgrep -x gnome-shell || true").trim().lines()
                .filter { it.toIntOrNull() != null && it !in oldShellPids }
            val cmd = newPids.map { pid ->
                try { pid + ":" + File("/proc/" + pid + "/cmdline").readText().replace('\u0000', ' ') }
                catch (t: Throwable) { pid + ":<cmdline error>" }
            }.joinToString(" | ")
            val evidence = (wm + " | " + props + " | cmd=" + cmd).replace("\n", " | ")
            if (evidence != lastIdentityEvidence) {
                Log.i(TAG, "restart: WM identity evidence: " + evidence)
                lastIdentityEvidence = evidence
            }
            return props.contains("GNOME Shell") && cmd.contains("gnome-shell")
        }
        val proc2 = XServer.launchOrReuseGnomeSession(ctx, d, rootfs)
        val shellDeadline = System.currentTimeMillis() + 90_000
        var shellBack = false
        while (System.currentTimeMillis() < shellDeadline && proc2.isAlive) {
            if (shellIdentity()) { shellBack = true; break }
            Thread.sleep(10_000)
        }
        Log.i(TAG, "restart: FIRST launch shellBack=" + shellBack + " proc2.alive=" + proc2.isAlive + " retries=0")
        mirrorGuestLog() // preserve even on failure, before the host removes it
        Thread.sleep(20_000)
        Shot.dismissSystemDialogs(device)
        Shot.save("24-ubuntu-gnome-restarted")
        assertTrue("relaunched gnome session process died", proc2.isAlive)
        Log.i(TAG, "restart: root tree before interaction: " + xwin("xwininfo -root -tree").replace("\n", " | ").take(3500))
        Log.i(TAG, "restart: shell processes: " + xwin("pgrep -af gnome-shell || true").replace("\n", " | "))
        // Collect independent post-restart interaction even if a metadata
        // field is missing. Identity stays a final gate, never waived.

        // Qualify interaction AFTER restart, not just a live shell marker.
        device.click(540, 1200)
        device.swipe(30, 194, 32, 194, 25)
        val reopenedDeadline = System.currentTimeMillis() + 90_000
        var reopened: NauWin? = null
        while (System.currentTimeMillis() < reopenedDeadline) {
            reopened = nauVisible()
            if (reopened != null) break
            Thread.sleep(3_000)
        }
        Thread.sleep(10_000)
        Shot.save("24b-ubuntu-restart-files")
        assertTrue("finger press did not reopen Files after restart", reopened != null)
        val rw = reopened!!
        val re = frameExtents(rw.id)
        hideSoftKeyboardIfShown()
        p1 = probeX(300, 1200)
        p2 = probeX(700, 1700)
        Log.i(TAG, "restart: fresh mapping probes " + p1 + " and " + p2)
        val rg = mapToDevice(rw.x + re[0] + rw.w - re[0] - re[1] - 200, rw.y + re[2] + 20)
        assertTrue("restart drag mapping missing", rg != null)
        Log.i(TAG, "restart: drag geom=" + rw.x + "," + rw.y + " " + rw.w + "x" + rw.h + " extents=" + re + " target=" + rg)
        anrGuard("restart-drag")
        Log.i(TAG, "restart: nautilus ids before drag: " + windowIds("nautilus"))
        fingerDrag(rg!!)
        Log.i(TAG, "restart: pointer after drag=" + xwin("xdotool getmouselocation --shell").replace("\n", " | "))
        Log.i(TAG, "restart: nautilus ids after drag: " + windowIds("nautilus"))
        val moved = nauVisible()
        Shot.save("24c-ubuntu-restart-dragged")
        Log.i(TAG, "restart: finger drag id=" + rw.id + " (" + rw.x + "," + rw.y + ") -> " +
            moved?.let { it.id + " (" + it.x + "," + it.y + ")" })
        assertTrue("Files did not follow finger drag after restart", moved != null &&
            Math.abs(moved.x - rw.x + 60) < 30 && Math.abs(moved.y - rw.y - 120) < 30)
        assertTrue("renamed folder missing after session restart", File(rootfs, "root/LDOA-proof-renamed").isDirectory)
        chord(false, android.view.KeyEvent.KEYCODE_L)
        device.executeShellCommand("input text /root/LDOA-proof-renamed")
        device.pressEnter()
        Thread.sleep(5_000)
        Shot.save("24d-ubuntu-restart-folder-opened")
        Log.i(TAG, "UBUNTU-FILEOPS-PERSISTED session restart retained renamed folder; UI navigation captured")
        assertTrue("GNOME window manager identity/process missing after restart: " + lastIdentityEvidence, shellBack)
        Log.i(TAG, "UBUNTU-GNOME-RESTART-OK FIRST launch; live WM identity + finger Files launch + continuous drag")

        // 5. COLD-START decision path: the plinux_desktop pref was set when
        // the auto-enter test opened the desktop. Recreating MainActivity
        // (config change) re-runs its cold-start effect with a fresh VM ->
        // openDesktop must fire and find the session ALREADY RUNNING
        // (idempotent: bring forward, no second gnome-session on :0).
        // Supplementary signal only: the GATE is the workflow's host-side
        // force-stop + relaunch probe (manual install, no AGP teardown)
        // with a fresh session marker and post-settle pixels.
        val launchCountBefore = logFile.readText().lineSequence()
            .count { it.contains("gnome-launch: trying gnome-session") }
        // MainActivity intentionally forwards to X11, so ActivityScenario's
        // sustained RESUMED requirement is wrong for this entry activity.
        // Observe real lifecycle and recreate once on its first resume.
        val app = ctx.applicationContext as android.app.Application
        val recreated = java.util.concurrent.atomic.AtomicBoolean(false)
        val createdCount = java.util.concurrent.atomic.AtomicInteger(0)
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {
                if (a is MainActivity) createdCount.incrementAndGet()
            }
            override fun onActivityResumed(a: android.app.Activity) {
                if (a is MainActivity && recreated.compareAndSet(false, true)) a.recreate()
            }
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
            val recreateDeadline = System.currentTimeMillis() + 60_000
            while (createdCount.get() < 2 && System.currentTimeMillis() < recreateDeadline) Thread.sleep(1_000)
            Log.i(TAG, "reentry: actual MainActivity created count=" + createdCount.get() + " recreate requested=" + recreated.get())
            assertTrue("actual MainActivity was not recreated", recreated.get() && createdCount.get() >= 2)
            Thread.sleep(10_000)
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
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
        val launchCountAfter = logFile.readText().lineSequence()
            .count { it.contains("gnome-launch: trying gnome-session") }
        assertEquals("MainActivity recreation started a duplicate session", launchCountBefore, launchCountAfter)
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
