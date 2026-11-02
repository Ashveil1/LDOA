package dev.phonelinux.proto

import android.content.Context
import android.util.Log
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M1 entry-seam emulator test (custom-mobile lane entry lifecycle).
 * FRESH DISPOSABLE CI EMULATOR ONLY: setUp ASSERTS a clean prior world
 * (no tracked session, no markers, no recovery block) and fails with the
 * observed state instead of normalizing it away.
 *
 * Flow: picker entry (bounded scroll, never blind coords) -> MOBILE
 * start with mode-aware consent words -> desktop evidence = ACTUAL pixel
 * diversity + guest session log without FAIL CLOSED (a screenshot name
 * is intent, never the result) -> repeat open REUSES the same Process
 * handle -> GNOME open while mobile active is REFUSED -> Stop is
 * verified against the pre-Stop PID/starttime-owned descendant set
 * (identity absence in /proc), not just a null handle.
 *
 * Emulator-only evidence; NO smoothness/FPS/latency claim anywhere.
 */
@RunWith(AndroidJUnit4::class)
class MobileDesktopEntryTest {

    private lateinit var device: UiDevice
    private lateinit var ctx: Context

    companion object {
        private const val TAG = "MobileDesktopEntryTest"
        private const val START_BUDGET_MS = 15 * 60_000L
        private const val UI_WAIT_MS = 10_000L
    }

    @Before
    fun setUp() {
        val instr = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instr)
        ctx = instr.targetContext
        val prefs = ctx.getSharedPreferences("plinux_desktop", Context.MODE_PRIVATE)
        // Verify, never normalize: a non-fresh world fails the run here.
        assertEquals("fresh world required: tracked session present", null, XServer.sessionProcess)
        assertFalse("fresh world required: attempt in flight", XServer.preparing)
        assertFalse("fresh world required: ubuntu marker set", prefs.getBoolean("ubuntu_desktop_active", false))
        assertFalse("fresh world required: mobile marker set", prefs.getBoolean("mobile_desktop_active", false))
        assertFalse("fresh world required: recovery block set", prefs.getBoolean("mobile_recovery_blocked", false))

        // Rootfs bootstrap: the mobile-desktop picker button exists ONLY when
        // Ubuntu is installed; a fresh emulator has none, and no button means
        // no consent flow to exercise. Provision the BASE distro up front
        // (same DistroInstaller path as MobileShellSpikeTest); the MOBILE
        // package's first-run consent ("Download and start") stays in-flow.
        val installer = DistroInstaller(ctx)
        if (!installer.isInstalled(Distros.UBUNTU)) {
            var last: InstallState = InstallState.NotInstalled
            val ok = kotlinx.coroutines.runBlocking {
                installer.install(Distros.UBUNTU) { last = it }
            }
            assertTrue("ubuntu rootfs provision failed at state=$last", ok)
        }
        assertTrue("ubuntu rootfs installed (mobile button requires it)",
            installer.isInstalled(Distros.UBUNTU))
    }

    @After
    fun tearDown() {
        exportGuestLogs("pre-stop")
        try { XServer.stopDesktopMode(ctx) } catch (t: Throwable) { }
        exportGuestLogs("post-stop")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SessionService.stop(ctx)
        XServer.resetOwnershipStateForTest()
    }

    /** Guest-log preservation into the runner-collected directory. Route
     *  grounded in run 41449407107's own runner log: "Copying files from
     *  device to host: /sdcard/Android/media/dev.phonelinux.proto/
     *  additional_test_output to .../app/build/outputs/
     *  connected_android_test_additional_output/..." - pulled even on failed
     *  assertions, unlike the lane's post-uninstall run-as pulls ("unknown
     *  package"). Called pre-stop (stop can truncate/replace logs) and
     *  post-stop (captures teardown lines). Per-file outcome and a missing
     *  output dir are logged explicitly; auxiliary, never masks the real
     *  failure. */
    private fun exportGuestLogs(stage: String) {
        try {
            val out = ctx.getExternalMediaDirs().firstOrNull()
                ?.let { File(it, "additional_test_output") }
            if (out == null) {
                Log.i(TAG, "m1-evidence: $stage guest-log export SKIPPED - no external media dir")
                return
            }
            if (!out.isDirectory && !out.mkdirs()) {
                Log.i(TAG, "m1-evidence: $stage guest-log export SKIPPED - cannot create " + out.absolutePath)
                return
            }
            for (name in listOf("mobile-desktop.log", "xserver.log")) {
                val f = File(ctx.filesDir, name)
                if (f.isFile) {
                    f.copyTo(File(out, name), overwrite = true)
                    Log.i(TAG, "m1-evidence: $stage exported " + name + " (" + f.length() + " bytes)")
                } else {
                    Log.i(TAG, "m1-evidence: $stage " + name + " ABSENT - nothing to export")
                }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "m1-evidence: $stage guest-log export failed (auxiliary): " + t.javaClass.simpleName)
        }
    }

    private fun tapByDesc(desc: String) {
        var obj = device.wait(Until.findObject(By.desc(desc)), 2_000)
        if (obj == null) {
            val sc = UiScrollable(UiSelector().scrollable(true)).setMaxSearchSwipes(6)
            if (sc.scrollIntoView(UiSelector().description(desc))) {
                obj = device.wait(Until.findObject(By.desc(desc)), UI_WAIT_MS)
            }
        }
        assertNotNull("affordance reachable (bounded scroll): $desc", obj)
        obj.click()
        device.waitForIdle(1_000)
    }

    /** Same bounded-scroll tap, matched on VISIBLE TEXT (no contentDescription
     *  on the consent confirm button in MainActivity). */
    private fun tapByText(text: String) {
        var obj = device.wait(Until.findObject(By.text(text)), 2_000)
        if (obj == null) {
            val sc = UiScrollable(UiSelector().scrollable(true)).setMaxSearchSwipes(6)
            if (sc.scrollIntoView(UiSelector().text(text))) {
                obj = device.wait(Until.findObject(By.text(text)), UI_WAIT_MS)
            }
        }
        assertNotNull("affordance reachable (bounded scroll, by text): $text", obj)
        obj.click()
        device.waitForIdle(1_000)
    }

    /** /proc/<pid>/stat content with the errno preserved: ENOENT = the
     *  process is GONE (the only clean "dead"); any other errno is UNKNOWN,
     *  never assumed dead. Os.open exposes the real errno; File.exists()
     *  cannot distinguish ENOENT from permission failures. */
    private fun readProcStat(pid: Int): Pair<String?, Int?> {
        val fd = try {
            android.system.Os.open("/proc/$pid/stat", android.system.OsConstants.O_RDONLY, 0)
        } catch (e: android.system.ErrnoException) { return null to e.errno }
        try {
            val sb = StringBuilder()
            val buf = ByteArray(4096)
            while (true) {
                val n = android.system.Os.read(fd, buf, 0, buf.size)
                if (n <= 0) break
                sb.append(String(buf, 0, n, Charsets.US_ASCII))
            }
            return sb.toString() to null
        } catch (e: android.system.ErrnoException) { return null to e.errno }
        finally { try { android.system.Os.close(fd) } catch (t: Throwable) { } }
    }

    /** pid -> starttime snapshot of the session root and every current
     *  descendant (ppid walk over /proc), for post-Stop identity checks.
     *  Non-ENOENT read failures land in [unreadable] and make the coverage
     *  INCOMPLETE - the caller fails rather than trusting a partial tree. */
    private fun pidTreeSnapshot(rootPid: Int, unreadable: MutableMap<Int, String>): Map<Int, String> {
        val stats = HashMap<Int, Pair<Int, String>>() // pid -> (ppid, starttime)
        File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) }?.forEach { f ->
            val pid = f.name.toInt()
            val (content, errno) = readProcStat(pid)
            if (content == null) {
                if (errno != android.system.OsConstants.ENOENT) unreadable[pid] = "errno=$errno"
                return@forEach
            }
            val close = content.lastIndexOf(')')
            val rest = if (close >= 0) content.substring(close + 1).trim().split(Regex("\\s+")) else emptyList()
            if (rest.size > 20) stats[pid] = rest[1].toInt() to rest[19]
            else unreadable[pid] = "unparseable stat"
        }
        val out = LinkedHashMap<Int, String>()
        var frontier = listOf(rootPid)
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<Int>()
            for ((pid, ps) in stats) {
                if (ps.first in frontier && pid !in out) { out[pid] = ps.second; next.add(pid) }
            }
            frontier = next
        }
        stats[rootPid]?.let { out[rootPid] = it.second }
        return out
    }

    private fun parseStartTime(statText: String): String? {
        val close = statText.lastIndexOf(')')
        if (close < 0) return null
        val rest = statText.substring(close + 1).trim().split(Regex("\\s+"))
        return if (rest.size > 19) rest[19] else null
    }

    /** starttime for one pid; null when ENOENT (gone) or unreadable -
     *  identity-unchanged assertions treat null as a FAIL, never as dead. */
    private fun procStartTime(pid: Int): String? = readProcStat(pid).first?.let { parseStartTime(it) }

    /** (raw excerpt, focused) where focused is TRUE only when every
     *  mCurrentFocus line names the vendored Xlorie display activity and no
     *  mFocusedApp line contradicts it; a dumpsys read failure or an
     *  unparseable display state is FALSE, never a pass. Mirrors the
     *  tri-state parser in VirglGnomeSessionTest (full dump filtered
     *  in-process: executeShellCommand has no shell). */
    private fun xlorieFocusState(): Pair<String, Boolean> {
        val raw = try { device.executeShellCommand("dumpsys window") } catch (t: Throwable) {
            return "focus-dumpsys-failed: ${t.javaClass.simpleName}" to false
        }
        fun mentions(l: String) =
            l.contains("com.termux.x11.MainActivity") || l.contains("com.termux.x11/.MainActivity")
        val cur = raw.lineSequence().filter { it.contains("mCurrentFocus") }.toList()
        val app = raw.lineSequence().filter { it.contains("mFocusedApp") }.toList()
        val ok = cur.isNotEmpty() && cur.all { mentions(it) } && app.all { mentions(it) }
        val joined = (cur + app).joinToString(" | ").replace('\n', ' ').take(300)
        return (if (joined.isNotEmpty()) joined else raw.replace('\n', ' ').take(300)) to ok
    }

    /** Distinct-color count over a coarse grid: a uniform (black/frozen)
     *  screen is NOT desktop evidence. */
    private fun distinctSampledColors(b: Bitmap): Int {
        val seen = HashSet<Int>()
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) { seen.add(b.getPixel(x, y)); x += 32 }
            y += 32
        }
        return seen.size
    }

    @Test
    fun mobileEntryLifecycle() {
        val prefs = ctx.getSharedPreferences("plinux_desktop", Context.MODE_PRIVATE)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // UiDevice-only helper: instrumentation thread is the correct
            // place; onActivity would block the app UI thread.
            Shot.dismissSystemDialogs(device)

            // 1. Picker entry (bounded scroll), then the mobile start.
            tapByDesc("show-picker")
            tapByDesc("open-mobile-desktop")
            val openTapAt = System.currentTimeMillis()
            val consent = device.wait(Until.findObject(By.textContains("mobile desktop (prototype)")), 3_000)
            if (consent != null) {
                Shot.save("m1-intent-mobile-consent")
                tapByText("Download and start")
            }

            var proc: Process? = null
            val deadline = System.currentTimeMillis() + START_BUDGET_MS
            while (System.currentTimeMillis() < deadline) {
                proc = XServer.sessionProcess
                if (proc != null && proc.isAlive && prefs.getBoolean("mobile_desktop_active", false)) break
                Thread.sleep(5_000)
            }
            val liveAt = System.currentTimeMillis()
            assertTrue("mobile session live + marker set within budget",
                proc != null && proc!!.isAlive && prefs.getBoolean("mobile_desktop_active", false))
            Log.i(TAG, "m1-stage: session-live marker observed at +" + (liveAt - openTapAt) +
                "ms after open tap - marker is NOT shell-ready, capture stage follows")
            val rootPid = XServer.sessionRootPidForTest
            assertTrue("tracked root pid captured", rootPid > 0)
            val launchRootStart = procStartTime(rootPid)
            assertNotNull("launch root /proc stat readable at start", launchRootStart)

            // Desktop evidence, all labeled precisely:
            //  a) pixel NON-UNIFORMITY is a NEGATIVE gate only - a uniform
            //     (black/frozen) screen fails; non-uniformity alone proves
            //     nothing about desktop content (dialogs/status bars pass too).
            //  b) WM evidence at capture instant: current focus line from
            //     dumpsys, surfaced raw; must exist and not be the launcher.
            //  c) guest log exists with no FAIL CLOSED marker.
            //  The Shot capture is a different instant than the pixel sample;
            //  claims stay separate. No "real desktop" claim from these.
            Shot.save("m1-intent-post-start-capture")
            val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            assertNotNull("post-start screenshot", bmp)
            val distinct: Int = try { distinctSampledColors(bmp!!) } finally { bmp!!.recycle() }
            // Pixel content is OBSERVATION ONLY: a color count is not desktop
            // identity (run 41449407107: the approved Phase-A shell rendered
            // jwm tray + xterm + empty pcmanfm desktop = 13 raw grid colors).
            // Content qualification is manual, from the saved shots. The only
            // hard gate retained is the uniform-screen NEGATIVE observation:
            // a one/two-color screen means nothing drew at all.
            Log.i(TAG, "m1-observe: capture stage=post-start at +" +
                (System.currentTimeMillis() - openTapAt) + "ms after open tap (+" +
                (System.currentTimeMillis() - liveAt) + "ms after marker); 32px-grid distinct=" +
                distinct + " - observation only, no desktop claim")
            // Sampled uniformity ONLY: a grid color count says nothing about
            // rendering (blank X area + Android bars can be non-uniform; flat
            // content can be near-uniform). The hard gate is limited to a
            // SAMPLED-UNIFORM screen observation; content review stays manual.
            assertTrue("sampled screen NOT uniform on the 32px grid (uniformity" +
                " observation only, no rendering conclusion; distinct=$distinct)", distinct > 2)
            val (focusRaw, xlorieFocused) = xlorieFocusState()
            assertTrue("focused window IS the Xlorie display activity" +
                " (positive component match, no contradiction; raw: $focusRaw)",
                xlorieFocused)
            val log = File(ctx.filesDir, "mobile-desktop.log").let { if (it.isFile) it.readText() else "" }
            assertTrue("guest session log exists", log.isNotEmpty())
            assertFalse("guest session log has no FAIL CLOSED marker", log.contains("FAIL CLOSED"))
            Shot.save("m1-evidence-post-start-screen")

            // 2. Repeat open REUSES the tracked session (same handle).
            device.pressBack()
            device.waitForIdle(1_000)
            tapByDesc("show-picker")
            tapByDesc("open-mobile-desktop")
            Thread.sleep(8_000)
            assertTrue("repeat open REUSED the tracked session (same handle, alive)",
                XServer.sessionProcess === proc && proc!!.isAlive)

            // 3. Cross-mode refusal while mobile is active.
            val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
            scenario.onActivity { a ->
                XServer.openDesktop(a, Distros.UBUNTU,
                    onError = { m -> errors.add(m) },
                    mode = XServer.DesktopMode.GNOME)
            }
            var waited = 0L
            while (errors.isEmpty() && waited < 20_000) { Thread.sleep(500); waited += 500 }
            assertTrue("GNOME open refused while MOBILE active: $errors",
                errors.any { it.contains("already running") || it.contains("unknown state") })
            assertTrue("mobile session untouched by refused GNOME open",
                XServer.sessionProcess === proc && proc!!.isAlive)

            // 4. Stop. Refresh the tree IMMEDIATELY before Stop (the world
            //    may have changed during the waits above) and assert the
            //    tracked root identity is still the launch identity.
            assertEquals("launch root identity unchanged at Stop (pid+starttime)",
                launchRootStart, procStartTime(rootPid))
            val preStopUnreadable = LinkedHashMap<Int, String>()
            val preStopTree = pidTreeSnapshot(rootPid, preStopUnreadable)
            assertTrue("pre-Stop tree captured root", preStopTree.containsKey(rootPid))
            assertTrue("pre-Stop /proc scan coverage complete (non-ENOENT unreadable: $preStopUnreadable)",
                preStopUnreadable.isEmpty())
            XServer.stopDesktopMode(ctx)
            assertFalse("mobile marker cleared by Stop", prefs.getBoolean("mobile_desktop_active", false))
            assertFalse("no recovery block (verified owned cleanup)", prefs.getBoolean("mobile_recovery_blocked", false))
            assertNull("ownership released after Stop", XServer.sessionProcess)
            val survivors = LinkedHashMap<Int, String>()
            val unknown = LinkedHashMap<Int, String>()
            for ((pid, start) in preStopTree) { // scoped to the captured tree only - no universal reap claim
                val (content, errno) = readProcStat(pid)
                if (content == null) {
                    if (errno == android.system.OsConstants.ENOENT) continue // gone: the only clean "dead"
                    unknown[pid] = "errno=$errno"; continue
                }
                val st = parseStartTime(content)
                if (st == null) unknown[pid] = "unparseable stat"
                else if (st == start) survivors[pid] = st
            }
            assertTrue("post-Stop /proc state UNKNOWN (not assumed dead): $unknown", unknown.isEmpty())
            assertEquals("no pre-Stop tree pid survives with the same starttime: $survivors",
                emptyMap<Int, String>(), survivors)
            Shot.save("m1-evidence-post-stop")
        }
    }
}
