package dev.phonelinux.proto

import android.content.Context
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
    }

    @After
    fun tearDown() {
        try { XServer.stopDesktopMode(ctx) } catch (t: Throwable) { }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SessionService.stop(ctx)
        XServer.resetOwnershipStateForTest()
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

    /** pid -> starttime snapshot of the session root and every current
     *  descendant (ppid walk over /proc), for post-Stop identity checks. */
    private fun pidTreeSnapshot(rootPid: Int): Map<Int, String> {
        val stats = HashMap<Int, Pair<Int, String>>() // pid -> (ppid, starttime)
        File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) }?.forEach { f ->
            try {
                val t = File(f, "stat").readText()
                val close = t.lastIndexOf(')')
                val rest = t.substring(close + 1).trim().split(Regex("\\s+"))
                if (rest.size > 20) stats[f.name.toInt()] = rest[1].toInt() to rest[19]
            } catch (t: Throwable) { }
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
            scenario.onActivity { Shot.dismissSystemDialogs(device) }

            // 1. Picker entry (bounded scroll), then the mobile start.
            tapByDesc("show-picker")
            tapByDesc("open-mobile-desktop")
            val consent = device.wait(Until.findObject(By.textContains("mobile desktop (prototype)")), 3_000)
            if (consent != null) {
                Shot.save("m1-intent-mobile-consent")
                tapByDesc("Download and start")
            }

            var proc: Process? = null
            val deadline = System.currentTimeMillis() + START_BUDGET_MS
            while (System.currentTimeMillis() < deadline) {
                proc = XServer.sessionProcess
                if (proc != null && proc.isAlive && prefs.getBoolean("mobile_desktop_active", false)) break
                Thread.sleep(5_000)
            }
            assertTrue("mobile session live + marker set within budget",
                proc != null && proc!!.isAlive && prefs.getBoolean("mobile_desktop_active", false))
            val rootPid = XServer.sessionRootPidForTest
            val preStopTree = pidTreeSnapshot(rootPid)
            assertTrue("tracked root pid captured", rootPid > 0 && preStopTree.containsKey(rootPid))

            // Desktop evidence: ACTUAL pixels (diversity) + guest log w/o
            // fail-closed markers. The Shot name is intent, not a result.
            Shot.save("m1-intent-post-start-capture")
            val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            assertNotNull("post-start screenshot", bmp)
            val distinct = distinctSampledColors(bmp!!)
            val log = File(ctx.filesDir, "mobile-desktop.log").let { if (it.isFile) it.readText() else "" }
            assertTrue("guest session log exists", log.isNotEmpty())
            assertFalse("guest session log has no FAIL CLOSED marker", log.contains("FAIL CLOSED"))
            assertTrue("desktop draw evidence: non-uniform pixels (distinct=$distinct)", distinct > 32)
            Shot.save("m1-evidence-desktop-draw")

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

            // 4. Stop: verified against the PID/starttime descendant set.
            XServer.stopDesktopMode(ctx)
            assertFalse("mobile marker cleared by Stop", prefs.getBoolean("mobile_desktop_active", false))
            assertFalse("no recovery block (verified owned cleanup)", prefs.getBoolean("mobile_recovery_blocked", false))
            assertNull("ownership released after Stop", XServer.sessionProcess)
            val survivors = preStopTree.filter { (pid, start) ->
                try {
                    val t = File("/proc/$pid/stat").readText()
                    val close = t.lastIndexOf(')')
                    t.substring(close + 1).trim().split(Regex("\\s+"))[19] == start
                } catch (t: Throwable) { false }
            }
            assertEquals("no pre-Stop tree pid survives with the same starttime: $survivors",
                emptyMap<Int, String>(), survivors)
            Shot.save("m1-evidence-post-stop")
        }
    }
}
