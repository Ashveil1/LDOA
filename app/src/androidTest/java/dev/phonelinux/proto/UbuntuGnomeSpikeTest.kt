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
        var p1 = probeX(300, 1200)
        var p2 = probeX(700, 1700)
        Log.i(TAG, "dock-interact: mapping probes dev(300,1200)->" + p1 + " dev(700,1700)->" + p2)
        fun mapToDevice(x: Int, y: Int): Pair<Int, Int>? {
            val first = p1 ?: return null
            val second = p2 ?: return null
            val ax = (700 - 300).toDouble() / (second.first - first.first)
            val bx = 300 - ax * first.first
            val ay = (1700 - 1200).toDouble() / (second.second - first.second)
            val by = 1200 - ay * first.second
            return Pair((ax * x + bx).toInt(), (ay * y + by).toInt())
        }
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
            event(android.view.MotionEvent.ACTION_DOWN, from.first.toFloat(), from.second.toFloat())
            android.os.SystemClock.sleep(600)
            for (step in 1..30) {
                event(android.view.MotionEvent.ACTION_MOVE,
                    from.first - 60f * step / 30, from.second + 120f * step / 30)
                android.os.SystemClock.sleep(25)
            }
            event(android.view.MotionEvent.ACTION_UP, from.first - 60f, from.second + 120f)
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
        val ctrl = android.view.KeyEvent.META_CTRL_ON
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_N, ctrl or android.view.KeyEvent.META_SHIFT_ON)
        Thread.sleep(2_000)
        device.executeShellCommand("input text LDOA-proof-created")
        device.pressEnter()
        Thread.sleep(5_000)
        Shot.save("23b-ubuntu-folder-created")
        assertTrue("Files create-folder UI did not create directory",
            File(rootfs, "root/LDOA-proof-created").isDirectory)
        // Newly created folder is selected in Nautilus.
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_F2)
        Thread.sleep(2_000)
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_A, ctrl)
        device.executeShellCommand("input text LDOA-proof-renamed")
        device.pressEnter()
        Thread.sleep(5_000)
        Shot.save("23c-ubuntu-folder-renamed")
        assertTrue("Files rename UI did not rename directory",
            File(rootfs, "root/LDOA-proof-renamed").isDirectory &&
                !File(rootfs, "root/LDOA-proof-created").exists())
        Log.i(TAG, "UBUNTU-FILEOPS-OK UI folder create and rename independently verified")

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
        p1 = probeX(300, 1200)
        p2 = probeX(700, 1700)
        Log.i(TAG, "restart: fresh mapping probes " + p1 + " and " + p2)
        val rg = mapToDevice(rw.x + re[0] + rw.w - re[0] - re[1] - 200, rw.y + re[2] + 20)
        assertTrue("restart drag mapping missing", rg != null)
        Log.i(TAG, "restart: drag geom=" + rw.x + "," + rw.y + " " + rw.w + "x" + rw.h + " extents=" + re + " target=" + rg)
        fingerDrag(rg!!)
        Log.i(TAG, "restart: pointer after drag=" + xwin("xdotool getmouselocation --shell").replace("\n", " | "))
        val moved = nauVisible()
        Shot.save("24c-ubuntu-restart-dragged")
        Log.i(TAG, "restart: finger drag (" + rw.x + "," + rw.y + ") -> " + moved?.let { it.x.toString() + "," + it.y })
        assertTrue("Files did not follow finger drag after restart", moved != null &&
            Math.abs(moved.x - rw.x + 60) < 30 && Math.abs(moved.y - rw.y - 120) < 30)
        assertTrue("renamed folder missing after session restart", File(rootfs, "root/LDOA-proof-renamed").isDirectory)
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_L, ctrl)
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
