package dev.phonelinux.proto

import android.content.Context
import android.content.Intent
import android.util.Log
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiObjectNotFoundException
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

    /** Backstop evidence for failures OUTSIDE the tap sites (setUp asserts,
     *  stage asserts): fires for any test failure not already captured at a
     *  precise site. Runs AFTER tearDown (JUnit rule ordering), so its
     *  screen state is post-teardown - the tap-site captures remain the
     *  failure-instant record; this hook never replaces them. */
    @get:org.junit.Rule val testName = org.junit.rules.TestName()
    @get:org.junit.Rule
    val failureEvidenceHook = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable?, description: org.junit.runner.Description?) {
            if (!::device.isInitialized || !::ctx.isInitialized) return
            if (failureEvidenceSite != null) return
            try {
                captureFailureEvidence("junit-hook-" + (description?.methodName ?: "unknown"))
            } catch (t: Throwable) {
                Log.i(TAG, "m1-evidence: junit-hook capture failed (auxiliary): " + t.javaClass.simpleName)
            }
        }
    }

    companion object {
        private const val TAG = "MobileDesktopEntryTest"
        private const val START_BUDGET_MS = 15 * 60_000L
        // xdiag diagnostic: asset pins + drain tail cap; all BUDGET and
        // truncation arithmetic lives in XdiagPolicy (testShared, unit-
        // tested off-device). The method early-skips in setUp unless
        // dispatch-gated, so normal runs pay no runtime beyond the
        // assume check itself.
        private const val XDIAG_TAIL_CAP = 64 * 1024
        private const val XDIAG_PROTO_COUNT = 28
        private const val XDIAG_XTRACE_SHA256 =
            "2117568790d9ebd9983d61cc58de8374b3759f442e08148ce287a3411ddaf489"
        private const val UI_WAIT_MS = 10_000L
    }

    @Before
    fun setUp() {
        // xdiag diagnostic methods are dispatch-gated: skip BEFORE any
        // device mutation (pm grant) or rootfs bootstrap, so a normal
        // run pays only this assume check for them (JUnit4 treats an
        // assumption failure in @Before as a skipped test).
        if (testName.methodName.startsWith("mobileEntryXdiag")) {
            org.junit.Assume.assumeTrue(
                "xdiag capture is dispatch-gated (ldoa_xdiag=true) - skipped in normal runs",
                InstrumentationRegistry.getArguments().getString("ldoa_xdiag") == "true")
        }
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

        // Deterministic permission setup - ENFORCED disposable-CI-emulator
        // gate, not a comment: pm grant MUTATES device permission state and
        // must never touch a physical/personal device. Require BOTH the
        // explicit runner opt-in (-e ldoa_ci_emulator true, set only by the
        // CI workflow) AND emulator build indicators; fail closed BEFORE any
        // mutation. MainActivity requests POST_NOTIFICATIONS at launch; on a
        // fresh emulator its GrantPermissionsActivity covers the picker and
        // is NOT the launcher-ANR dialog the ONE-dismissal budget exists for
        // (run 41457173978 failed at the FIRST tap on exactly this, zero
        // dialog clicks - correct fail-closed, wrong setup). Granting keeps
        // the dialog policy untouched: it is never dismissed; the ONE shared
        // launcher-ANR budget covers verified ANRs from the first gate on.
        val ciOptIn = InstrumentationRegistry.getArguments()
            .getString("ldoa_ci_emulator") == "true"
        val looksEmulator = listOf(android.os.Build.FINGERPRINT, android.os.Build.MODEL,
            android.os.Build.PRODUCT).any {
            it.contains("sdk", true) || it.contains("emulator", true) || it.contains("generic", true)
        }
        assertTrue("refusing permission mutation: not an opted-in CI emulator" +
            " (runnerArg=" + ciOptIn + ", fingerprint=" + android.os.Build.FINGERPRINT + ")",
            ciOptIn && looksEmulator)
        device.executeShellCommand("pm grant " + ctx.packageName + " android.permission.POST_NOTIFICATIONS")
        val notifGranted = device.executeShellCommand("dumpsys package " + ctx.packageName)
            .contains("android.permission.POST_NOTIFICATIONS: granted=true")
        assertTrue("deterministic setup: POST_NOTIFICATIONS grant did not land (disposable CI emulator)", notifGranted)
        Log.i(TAG, "m1-setup: POST_NOTIFICATIONS pre-granted (deterministic, CI emulator only)")

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
        // JUnit4 runs @After in a finally around @Before (RunAfters wraps
        // RunBefores): an assumption skip in setUp STILL lands here with
        // ctx/device UNINITIALIZED. Guard first - never touch lateinit
        // state after an early skip.
        if (!::ctx.isInitialized || !::device.isInitialized) return
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
            // Stage-distinct subdirectory: pre-stop and post-stop exports
            // must never overwrite each other (the launch evidence IS the
            // pre-stop copy). Each file is independent - one failure must
            // not skip the other.
            val stageDir = File(out, stage)
            if (!stageDir.isDirectory && !stageDir.mkdirs()) {
                Log.i(TAG, "m1-evidence: $stage guest-log export SKIPPED - cannot create " + stageDir.absolutePath)
                return
            }
            for (name in listOf("mobile-desktop.log", "xserver.log")) {
                try {
                    val f = File(ctx.filesDir, name)
                    if (f.isFile) {
                        f.copyTo(File(stageDir, name), overwrite = true)
                        Log.i(TAG, "m1-evidence: $stage exported " + name + " (" + f.length() + " bytes)")
                    } else {
                        Log.i(TAG, "m1-evidence: $stage " + name + " ABSENT - nothing to export")
                    }
                } catch (t: Throwable) {
                    Log.i(TAG, "m1-evidence: $stage " + name + " export failed (auxiliary): " + t.javaClass.simpleName)
                }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "m1-evidence: $stage guest-log export failed (auxiliary): " + t.javaClass.simpleName)
        }
    }

    /** Failure evidence at ANY stage, pre-consent included (run 41457173978
     *  failed at the first tap with zero captured screen state): screenshot
     *  via the proven logcat channel, foreground package, raw focus excerpt,
     *  and a best-effort UI hierarchy into additional_test_output. Auxiliary:
     *  every piece is individually guarded and never masks the real failure. */
    @Volatile private var failureEvidenceSite: String? = null

    private fun captureFailureEvidence(site: String) {
        failureEvidenceSite = site
        // Filenames/shell args get a sanitized token ONLY ([A-Za-z0-9_-]:
        // a label like "Download and start" would break the tmp path
        // argument and the saved name); the unique suffix prevents a stale
        // dump from an earlier capture being archived as THIS failure.
        // The original human label rides the log lines, never the paths.
        val token = site.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val unique = token + "-" + System.currentTimeMillis()
        try { Shot.save("m1-failure-" + unique) } catch (t: Throwable) {
            Log.i(TAG, "m1-evidence: failure shot at '" + site + "' failed (auxiliary): " + t.javaClass.simpleName)
        }
        val fg = try { device.currentPackageName } catch (t: Throwable) { "unavailable" }
        val focus = xlorieFocusState()
        Log.i(TAG, "m1-evidence: failure at '" + site + "' (token=" + unique + ") foreground=" + fg +
            " focus=" + focus.second + " raw=" + focus.first)
        // Full untruncated dump alongside the truncated log excerpt (the
        // excerpt's 300-char cut loses section headers - run 41490601274).
        val fullDump = try { device.executeShellCommand("dumpsys window") } catch (t: Throwable) { "" }
        archiveFullFocusDump(site, fullDump)
        try {
            val out = ctx.getExternalMediaDirs().firstOrNull()
                ?.let { File(it, "additional_test_output") }
            if (out != null && (out.isDirectory || out.mkdirs())) {
                // UiDevice.dumpWindowHierarchy rides the test's OWN
                // UiAutomation connection. A shell `uiautomator dump`
                // cannot coexist with it - the shell process crashes
                // ("UiAutomationService already registered", run
                // 41468632672) and yields nothing. Validate the written
                // file: an error payload must never be archived as .xml.
                val dest = File(out, "m1-failure-" + unique + "-uihierarchy.xml")
                device.dumpWindowHierarchy(dest)
                val xml = if (dest.isFile) dest.readText() else ""
                if (xml.startsWith("<?xml") && xml.contains("<hierarchy")) {
                    Log.i(TAG, "m1-evidence: UI hierarchy at '" + site + "' exported (" + xml.length + " chars)")
                } else {
                    dest.delete()
                    Log.i(TAG, "m1-evidence: hierarchy capture at '" + site +
                        "' INVALID (not a hierarchy dump; first chars: " + xml.take(80) + ")")
                }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "m1-evidence: hierarchy capture at '" + site + "' failed (auxiliary): " + t.javaClass.simpleName)
        }
    }

    // Shared obstruction budget for the WHOLE M1 test: exactly ONE verified
    // launcher-ANR Wait dismissal, armed from the earliest entry/navigation
    // obstruction (the readiness gate before the first tap) through the
    // final focus gate. Any recurrence after that one action fails closed;
    // nothing else is ever clicked (no watchers, no close-app, no launcher
    // suppress/replace, and never a blind tap on an unrelated permission
    // window - those stay evidence-only failures).
    private var anrDismissalsSpent = 0

    /** The one permitted obstruction action: a VERIFIED launcher-ANR Wait
     *  dismissal. Shot's detection evidence chain runs first; the click is
     *  the fresh in-container Wait; the shared test-wide budget is spent.
     *  Fails closed when the budget is already spent. */
    private fun dismissVerifiedLauncherAnrOnce(site: String) {
        if (anrDismissalsSpent > 0) {
            captureFailureEvidence(site)
            org.junit.Assert.fail("verified launcher-ANR dialog present at '" + site + "' after" +
                " the test's ONE dismissal was already spent (persistent or new recurrence -" +
                " instantaneous detection cannot distinguish) - fail closed")
        }
        // Pre-action DURABLE evidence: detection is not visual proof, so save
        // an observation screenshot + raw focus/foreground log BEFORE the one
        // permitted click. Deliberately NOT captureFailureEvidence - that
        // sets failureEvidenceSite and would suppress the true failure hook;
        // this is a recoverable action, observation only (Shot.save guarded).
        val preFg = try { device.currentPackageName } catch (t: Throwable) { "unavailable" }
        val preFocus = xlorieFocusState()
        try {
            Shot.save("m1-obstruction-anr-" + site.replace(Regex("[^A-Za-z0-9_-]"), "_") +
                "-" + System.currentTimeMillis())
        } catch (t: Throwable) {
            Log.i(TAG, "m1-obstruction: pre-action shot at '" + site + "' failed (auxiliary): " +
                t.javaClass.simpleName)
        }
        Log.i(TAG, "m1-obstruction: pre-action evidence at '" + site + "' foreground=" + preFg +
            " focus=" + preFocus.second + " raw=" + preFocus.first)
        val dismissed = Shot.dismissVerifiedLauncherAnr(device, maxDismissals = 1)
        anrDismissalsSpent += dismissed
        Log.i(TAG, "m1-obstruction: verified launcher-ANR dismissal at '" + site + "'=" +
            dismissed + " (shared budget " + anrDismissalsSpent + "/1 for the whole test)")
    }

    /** Readiness gate before ANY navigation: the app must be foreground
     *  (bounded wait for the launch transition) before scrolling. A wrong
     *  foreground (dialog, permission prompt, launcher) is EVIDENCE, not a
     *  reason to scroll blindly - capture and fail closed. */
    private fun requireForegroundApp(site: String) {
        device.wait(Until.hasObject(By.pkg(ctx.packageName)), UI_WAIT_MS)
        var fg = try { device.currentPackageName } catch (t: Throwable) { null }
        // Foreground blocked (before the first tap or later): if it is a
        // VERIFIED launcher ANR (evidence chain first, inside Shot) and the
        // shared budget is unspent, the single Wait dismissal happens HERE -
        // the earliest obstruction - then one bounded re-wait. Anything else
        // (permission window, launcher, unknown) falls through to evidence +
        // fail closed; a recurrence after the one dismissal also fails closed.
        if (fg != ctx.packageName && anrDismissalsSpent == 0 &&
            Shot.detectVerifiedLauncherAnr(device)) {
            dismissVerifiedLauncherAnrOnce(site)
            device.wait(Until.hasObject(By.pkg(ctx.packageName)), UI_WAIT_MS)
            fg = try { device.currentPackageName } catch (t: Throwable) { null }
        }
        // Post-spend: a verified ANR dialog fails closed EVEN WHEN the
        // foreground reads as our app - the dialog can hold window focus
        // while currentPackageName matches; never tap through it. Detection
        // is instantaneous: "persistent or new recurrence", no old-vs-new
        // assumption.
        if (anrDismissalsSpent > 0 && Shot.detectVerifiedLauncherAnr(device)) {
            captureFailureEvidence(site)
            org.junit.Assert.fail("verified launcher-ANR dialog present at '" + site + "' after" +
                " the test's ONE dismissal was spent (persistent or new recurrence -" +
                " instantaneous detection cannot distinguish; foreground='" + fg + "')" +
                " - fail closed, no taps through the dialog")
        }
        if (fg != ctx.packageName) {
            captureFailureEvidence(site)
            org.junit.Assert.fail("readiness at '" + site + "': foreground is '" + fg +
                "', not " + ctx.packageName + " - evidence captured, no blind scroll")
        }
    }

    /** Run 41510706613 proved the desc-matched node itself is NON-clickable
     *  (UiObject2 "Clicking on non-clickable object" on the open-mobile tap),
     *  so a wrapper-center tap is ambiguous. The clickable node being a
     *  DESCENDANT is source-inferred from the Compose modifier order
     *  (semantics wrapper outside the button's own clickable), NOT yet
     *  runtime-proven from a captured hierarchy - so resolution stays
     *  fail-closed: the matched node itself when clickable, else the SINGLE
     *  clickable descendant; zero or 2+ (or an ancestor-only mapping) =
     *  no tap, evidence, fail. */
    private fun resolveClickableNode(node: UiObject2): UiObject2? {
        if (node.isClickable) return node
        val found = mutableListOf<UiObject2>()
        val stack = java.util.ArrayDeque<UiObject2>()
        stack.add(node)
        while (!stack.isEmpty()) {
            val n = stack.removeFirst()
            for (c in n.children) {
                if (c.isClickable) found.add(c) else stack.add(c)
            }
        }
        if (found.size > 1) return null
        if (found.size == 1) return found[0]
        // This app's Compose semantics put clickable on the WRAPPER ABOVE the
        // content-described node (run 41522806092 hierarchy, e.g. show-picker:
        // View(clickable=true) > View(desc) + TextView + Button). Descendant-only
        // resolution then rejects a genuinely tappable row. Accept the ancestor
        // chain ONLY when unambiguous: exactly ONE clickable ancestor up to the
        // root AND its visible bounds contain the matched node's center - a
        // contained clickable ancestor IS the row's accessibility click target.
        // Guards (reviewed): 2+ clickable ancestors, non-containing or empty
        // bounds, disabled candidate, non-app package, or an incompletely
        // walked chain (depth cap hit): all fail closed.
        val center = node.visibleCenter
        var p = node.parent
        var candidate: UiObject2? = null
        var depth = 0
        while (p != null && depth < 12) {
            if (p.isClickable) {
                if (candidate != null) return null
                val vb = p.visibleBounds
                if (vb.width() <= 0 || vb.height() <= 0) return null
                if (!vb.contains(center.x, center.y)) return null
                if (!p.isEnabled) return null
                if (p.applicationPackage != ctx.packageName) return null
                candidate = p
            }
            p = p.parent
            depth++
        }
        if (p != null) return null
        return candidate
    }

    /** Shared bounded-scroll tap with readiness + evidence. UiScrollable
     *  THROWS UiObjectNotFoundException when no scrollable container exists
     *  at all (run 41457173978) - that escape is caught, evidenced, and
     *  reported as a wrong-screen failure instead of a bare exception. */
    private fun tapAffordance(label: String, by: BySelector, sel: UiSelector) {
        requireForegroundApp(label)
        var obj = device.wait(Until.findObject(by), 2_000)
        if (obj == null) {
            // Never swipe an unverified surface: with the X view up, swipes
            // inject into the focused guest (run 41476451666: arrow-key
            // escapes echoed into xterm). Scroll ONLY on the verified picker
            // component; anything else is evidence + fail closed.
            val comp = stableFocusedComponent(label)
            val lorie = lorieViewPresent()
            if (classifyPickerSurface(comp, lorie) != PickerSurface.PICKER_COMPONENT) {
                captureFailureEvidence(label)
                org.junit.Assert.fail("refusing bounded scroll for '" + label + "' on an" +
                    " unverified surface (component=" + comp + " lorieView=" + lorie +
                    ") - never inject swipes into a live session; evidence captured")
            }
            val sc = UiScrollable(UiSelector().scrollable(true)).setMaxSearchSwipes(6)
            val scrolled = try {
                sc.scrollIntoView(sel)
            } catch (e: UiObjectNotFoundException) {
                captureFailureEvidence(label)
                org.junit.Assert.fail("no scrollable container while seeking '" + label +
                    "' - wrong screen (evidence captured), never a blind scroll")
                false // unreachable (fail throws); keeps the try/catch Boolean-typed
            }
            if (scrolled) {
                obj = device.wait(Until.findObject(by), UI_WAIT_MS)
            }
        }
        if (obj == null) captureFailureEvidence(label)
        assertNotNull("affordance reachable (bounded scroll): $label", obj)
        // Pre-click sanity at COMPONENT level (not package-only), then a
        // FRESH find: a cached UiObject2's bounds can belong to a surface
        // that changed underneath it - clicking stale bounds on the X
        // surface injects into the live session.
        val preComp = stableFocusedComponent(label)
        val preLorie = lorieViewPresent()
        if (classifyPickerSurface(preComp, preLorie) != PickerSurface.PICKER_COMPONENT) {
            captureFailureEvidence(label)
            org.junit.Assert.fail("pre-click surface for '" + label + "' is not the" +
                " verified picker (component=" + preComp + " lorieView=" + preLorie +
                ") - no click; evidence captured")
        }
        val fresh = device.wait(Until.findObject(by), 1_000)
        if (fresh == null) {
            captureFailureEvidence(label)
            org.junit.Assert.fail("'" + label + "' vanished between find and click -" +
                " evidence captured, no stale-bounds click")
        }
        val target = try {
            resolveClickableNode(fresh)
        } catch (e: StaleObjectException) {
            captureFailureEvidence(label)
            org.junit.Assert.fail("'" + label + "' went STALE while resolving the clickable node" +
                " (surface changed under the find) - evidence captured, NO retry tap")
            return // unreachable (Assert.fail throws); keeps the compiler honest
        }
        if (target == null) {
            captureFailureEvidence(label)
            org.junit.Assert.fail("'" + label + "' resolved to no unambiguous clickable node" +
                " (matched node non-clickable, and no single clickable descendant or" +
                " center-containing clickable ancestor) - no wrapper-center tap; evidence captured")
            return // unreachable (Assert.fail throws); keeps the compiler honest
        }
        try {
            target.click()
        } catch (e: StaleObjectException) {
            captureFailureEvidence(label)
            org.junit.Assert.fail("'" + label + "' went STALE at click time" +
                " (surface changed under the tap) - evidence captured, NO duplicate click")
            return // unreachable (Assert.fail throws); keeps the compiler honest
        }
        device.waitForIdle(1_000)
    }

    private fun tapByDesc(desc: String) =
        tapAffordance(desc, By.desc(desc), UiSelector().description(desc))

    /** Same bounded-scroll tap, matched on VISIBLE TEXT (no contentDescription
     *  on the consent confirm button in MainActivity). */
    private fun tapByText(text: String) =
        tapAffordance(text, By.text(text), UiSelector().text(text))

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
    /** EXACT normalized "pkg/Class" token of the INTERACTION display's
     *  mCurrentFocus; null when dumpsys fails, the section is absent, or the
     *  token is unparseable - callers treat null as UNVERIFIED (fail closed),
     *  and classification is exact-token allowlist only, never substring
     *  matching. Focus is PER DISPLAY (AOSP DisplayContent.mCurrentFocus): a
     *  dump can carry several mCurrentFocus lines from different displays,
     *  so the raw dump is parsed per-section (interactionFocusToken) instead
     *  of trusting line order (run 41490601274 misclassified a transition
     *  dump that carried launcher + dev MainActivity). */
    private fun focusedComponent(): String? {
        // `dumpsys window displays` (per-arg dispatch, AOSP WMS.java ~6990)
        // returns ONLY the live display contents - a full `dumpsys window`
        // also replays the FROZEN last-ANR display state at the top (run
        // 41495139793: frozen launcher focus conflicted with the live
        // picker section forever). interactionFocusToken additionally
        // strips any frozen replay defensively.
        val raw = try { device.executeShellCommand("dumpsys window displays") } catch (t: Throwable) { return null }
        return interactionFocusToken(raw)
    }

    /** Bounded stability poll for the interaction display's focus token.
     *  The temporal decision is the PURE reduceStabilitySample (unit-tested):
     *  monotonic SystemClock.elapsedRealtime feeding (elapsed, token)
     *  samples - the deadline is checked BEFORE a result is accepted, an
     *  unreadable sample resets the streak, and only the same exact token
     *  on two consecutive in-budget samples (~400ms apart) confirms.
     *  Exhaustion archives the FULL untruncated `dumpsys window` (the
     *  300-char log excerpt loses section headers - run 41490601274) and
     *  returns null; callers fail closed. */
    private fun stableFocusedComponent(site: String, budgetMs: Long = 3_000): String? {
        val start = android.os.SystemClock.elapsedRealtime()
        var last: String? = null
        while (true) {
            val token = focusedComponent()
            when (val outcome = reduceStabilitySample(
                last, android.os.SystemClock.elapsedRealtime() - start, token, budgetMs)) {
                is StabilityOutcome.Confirmed -> return outcome.token
                is StabilityOutcome.Exhausted -> {
                    val raw = try { device.executeShellCommand("dumpsys window") } catch (t: Throwable) { "" }
                    archiveFullFocusDump(site, raw)
                    Log.i(TAG, "m1-nav: no stable interaction-display focus token at '" + site +
                        "' within " + budgetMs + "ms (last=" + last +
                        ", perDisplay(live)=" + parseFocusedTokensByDisplay(stripFrozenAnrReplay(raw)) + ")")
                    return null
                }
                is StabilityOutcome.Pending -> { last = outcome.lastToken; Thread.sleep(400) }
            }
        }
    }

    /** FULL raw `dumpsys window` into the archived additional_test_output
     *  dir (same sink as the failure hierarchy XML): section headers and
     *  every per-display focus line survive, unlike the truncated log
     *  excerpt. Auxiliary - a write failure is logged, never fatal. */
    private fun archiveFullFocusDump(site: String, raw: String) {
        try {
            val out = ctx.getExternalMediaDirs().firstOrNull()
                ?.let { File(it, "additional_test_output") }
            if (out != null && (out.isDirectory || out.mkdirs())) {
                val token = site.replace(Regex("[^A-Za-z0-9_-]"), "_")
                val dest = File(out, "m1-focusdump-" + token + "-" +
                    System.currentTimeMillis() + ".txt")
                dest.writeText(raw)
                Log.i(TAG, "m1-evidence: full dumpsys window archived at '" + site +
                    "' (" + raw.length + " chars)")
            }
        } catch (t: Throwable) {
            Log.i(TAG, "m1-evidence: full focus dump at '" + site +
                "' failed (auxiliary): " + t.javaClass.simpleName)
        }
    }

    /** ActivityRecord tokens of the dev MainActivity instances on the device
     *  (dumpsys activity activities). The X activity's component
     *  (com.termux.x11.MainActivity) cannot match this pattern. Empty on
     *  dumpsys failure - callers treat != exactly-1 as fail closed. */
    private fun mainActivityRecords(): Set<String> {
        val raw = try { device.executeShellCommand("dumpsys activity activities") } catch (t: Throwable) { return emptySet() }
        val re = Regex("ActivityRecord\\{(\\w+) u0 dev\\.phonelinux\\.proto/(?:dev\\.phonelinux\\.proto\\.|\\.)MainActivity\\b")
        return re.findAll(raw).map { it.groupValues[1] }.toSet()
    }

    /** Fresh picker UI state (no caching): the panel state survives in the
     *  preserved MainActivity instance, so after returning from the X view
     *  it can be EXPANDED already - both markers at once is contradictory,
     *  neither is absent; both fail closed via classifyPickerUi. */
    private fun pickerUiState(): PickerUiState =
        classifyPickerUi(device.hasObject(By.desc("show-picker")),
            device.hasObject(By.desc("open-mobile-desktop")))

    private fun lorieViewPresent(): Boolean =
        device.findObject(By.res("dev.phonelinux.proto:id/lorieView")) != null

    /** pressBack ONLY when the IME is genuinely shown (dumpsys-verified) and
     *  only on a verified picker surface: there it hides the IME. A blind
     *  back elsewhere hits app BackHandlers or is swallowed (UiFlowTest
     *  precedent + the X view's empty onBackPressed). */
    private fun dismissImeIfShownOnPicker(site: String) {
        // executeShellCommand has NO shell: a pipe/grep inside the command
        // string silently matches nothing (earlier lesson). Full dump, then
        // an in-process parse of the EXACT visibility line. UNKNOWN (dump
        // failed or no line) => NO back + evidence - never assume hidden.
        val dump = try { device.executeShellCommand("dumpsys input_method") } catch (t: Throwable) { "" }
        // Exact boolean field with token boundary: a line like
        // "mInputShown=true <other fields>" or any malformed value is
        // UNKNOWN, never silently "not shown".
        val visRe = Regex("^mInputShown=(true|false)$")
        val visibility = dump.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("mInputShown=") }
            ?.let { visRe.find(it)?.groupValues?.get(1) }
        when {
            visibility == null -> Log.i(TAG, "m1-nav: IME visibility UNKNOWN at '" + site +
                "' (no exact mInputShown=true|false line) - no back, evidence only")
            visibility == "true" -> {
                Log.i(TAG, "m1-nav: IME shown at '" + site + "' - hiding via back (picker verified)")
                device.pressBack()
                device.waitForIdle(1_000)
            }
            else -> Log.i(TAG, "m1-nav: IME not shown at '" + site + "' (" + visibility + ") - no back")
        }
    }

    /** Bounded, source-grounded return to the picker from the live X view.
     *  Source facts (vendored termux-x11 + run 41476451666 evidence):
     *  - com.termux.x11.MainActivity.onBackPressed is an EMPTY override:
     *    back is SWALLOWED in the X view (with the IME up, back hides the
     *    IME and never reaches it). The vendored Exit button sits on the
     *    not-connected stub - absent during a live session. There is NO
     *    in-X-view exit; pressBack here is proven useless, never retried.
     *  - The dev MainActivity instance stays in the SAME task below the X
     *    activity (XServer starts the X view with a plain startActivity).
     *    REORDER_TO_FRONT brings THAT instance forward: no new instance, no
     *    session touch (teardown is SessionService ACTION_STOP only), so
     *    the tracked sessionProcess/owner identity survives and a later
     *    open genuinely exercises the REUSE path - never a masquerade.
     *  - Swipes on the X surface inject into the focused guest (run
     *    41476451666: arrow-key escapes echoed into xterm) - this helper
     *    never scrolls; surface verdicts come from classifyPickerSurface. */
    private fun returnToPicker(site: String) {
        // Instance identity BEFORE any action: exactly one tracked dev
        // MainActivity instance (the scenario's). NEW_TASK|REORDER_TO_FRONT
        // alone does not prove instance preservation - the ActivityRecord
        // token set must be UNCHANGED after, or the navigation created a
        // new instance (breaking the scenario's tracked activity and any
        // reuse claim built on it).
        val beforeRecords = mainActivityRecords()
        val beforeProc = XServer.sessionProcess
        if (beforeRecords.size != 1) {
            captureFailureEvidence(site)
            org.junit.Assert.fail("return-to-picker at '" + site + "': expected exactly 1" +
                " tracked MainActivity instance, found " + beforeRecords.size + " " +
                beforeRecords + " - evidence captured, no navigation")
        }
        var comp: String? = null
        var lorie = false
        var attempt = 0
        // ONE overall return deadline (monotonic) covering every pass AND
        // the nested stability poll - the poll's own 3s budget is per-pass,
        // never a new lease on the whole navigation.
        val returnDeadline = android.os.SystemClock.elapsedRealtime() + 20_000
        fun returnDeadlineExceeded() {
            if (android.os.SystemClock.elapsedRealtime() < returnDeadline) return
            captureFailureEvidence(site)
            org.junit.Assert.fail("return-to-picker at '" + site + "': overall 20s" +
                " deadline exhausted (attempts=" + attempt + ") - evidence captured")
        }
        while (true) {
            returnDeadlineExceeded()
            // Verify FIRST and after EVERY action, including the last.
            comp = stableFocusedComponent(site)
            lorie = lorieViewPresent()
            returnDeadlineExceeded()
            when (classifyPickerSurface(comp, lorie)) {
                PickerSurface.PICKER_COMPONENT -> {
                    val afterRecords = mainActivityRecords()
                    if (afterRecords != beforeRecords) {
                        captureFailureEvidence(site)
                        org.junit.Assert.fail("MainActivity instance set changed across" +
                            " return-to-picker at '" + site + "' (before=" + beforeRecords +
                            ", after=" + afterRecords + ") - a new instance cannot carry" +
                            " the scenario's tracked activity; evidence captured")
                    }
                    if (XServer.sessionProcess !== beforeProc) {
                        captureFailureEvidence(site)
                        org.junit.Assert.fail("tracked session handle changed during" +
                            " return-to-picker at '" + site + "' - evidence captured")
                    }
                    // Known picker UI, not just the component string: the
                    // panel state survives in the preserved instance, so
                    // EITHER collapsed (show-picker) OR expanded
                    // (open-mobile-desktop) proves the picker; both at once
                    // (AMBIGUOUS) and neither (NEITHER) fail closed.
                    var uiState = pickerUiState()
                    val uiDeadline = System.currentTimeMillis() + 5_000
                    while (uiState == PickerUiState.NEITHER &&
                        System.currentTimeMillis() < uiDeadline) {
                        returnDeadlineExceeded()
                        Thread.sleep(500)
                        uiState = pickerUiState()
                    }
                    if (uiState != PickerUiState.COLLAPSED && uiState != PickerUiState.EXPANDED) {
                        captureFailureEvidence(site)
                        org.junit.Assert.fail("picker component verified at '" + site +
                            "' but picker UI state=" + uiState + " (need collapsed" +
                            " show-picker or expanded open-mobile-desktop) - evidence" +
                            " captured, no scroll inside navigation")
                    }
                    dismissImeIfShownOnPicker(site)
                    // A guarded back is still an action: re-verify the picker
                    // component AFTER it before declaring navigation done.
                    val postComp = stableFocusedComponent(site)
                    val postLorie = lorieViewPresent()
                    if (classifyPickerSurface(postComp, postLorie) !=
                        PickerSurface.PICKER_COMPONENT) {
                        captureFailureEvidence(site)
                        org.junit.Assert.fail("surface changed by the IME-hide back at '" +
                            site + "' (component=" + postComp + " lorieView=" + postLorie +
                            ") - evidence captured")
                    }
                    // Final gate: identity/UI checks and the post-IME stable
                    // poll all consumed return budget - the overall deadline
                    // must still hold BEFORE declaring success.
                    returnDeadlineExceeded()
                    Log.i(TAG, "m1-nav: picker verified at '" + site + "' (attempts=" +
                        attempt + ", component=" + postComp + ", instance=" + afterRecords +
                        ", session handle unchanged)")
                    return
                }
                PickerSurface.IN_X_VIEW -> {
                    if (attempt >= 2) {
                        captureFailureEvidence(site)
                        org.junit.Assert.fail("return-to-picker at '" + site + "': still in" +
                            " the X view after 2 REORDER_TO_FRONT attempts (component=" +
                            comp + " lorieView=" + lorie + ") - evidence captured")
                    }
                    attempt++
                    Log.i(TAG, "m1-nav: X view at '" + site + "' (component=" + comp +
                        " lorieView=" + lorie + ") - bringing the tracked MainActivity" +
                        " forward via REORDER_TO_FRONT (attempt " + attempt + "; back is" +
                        " a source-verified no-op in the X view; session untouched)")
                    ctx.startActivity(Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    device.waitForIdle(2_000)
                    Thread.sleep(1_000)
                }
                else -> {
                    // AMBIGUOUS or WRONG_SURFACE: no navigation authority.
                    captureFailureEvidence(site)
                    org.junit.Assert.fail("return-to-picker at '" + site + "': surface=" +
                        classifyPickerSurface(comp, lorie) + " (component=" + comp +
                        " lorieView=" + lorie + ") - evidence captured, no blind navigation")
                }
            }
        }
    }

    private fun xlorieFocusState(): Pair<String, Boolean> {
        // Live-only read: a full dump's frozen last-ANR replay would
        // conflict with the live section forever after any ANR (run
        // 41495139793).
        val raw = try { device.executeShellCommand("dumpsys window displays") } catch (t: Throwable) {
            return "focus-dumpsys-failed: ${t.javaClass.simpleName}" to false
        }
        fun mentions(l: String) =
            l.contains("com.termux.x11.MainActivity") || l.contains("com.termux.x11/.MainActivity")
        // Focus is PER DISPLAY (AOSP DisplayContent.mCurrentFocus, dumped
        // once per "Display: mDisplayId=N" section): scope to the
        // INTERACTION display's section - requiring EVERY display's focus
        // line to name Xlorie would fail on any multi-display device, and
        // line order is never authority. Legacy header-less dumps keep the
        // previous all-lines rule.
        val hasHeaders = raw.lineSequence().any { it.trim().startsWith("Display: mDisplayId=") }
        val cur: List<String>
        val app: List<String>
        if (hasHeaders) {
            var inDisplay0 = false
            val c = ArrayList<String>()
            val a = ArrayList<String>()
            for (line in raw.lineSequence()) {
                val t = line.trim()
                if (t.startsWith("Display: mDisplayId=")) {
                    inDisplay0 = t.removePrefix("Display: mDisplayId=")
                        .substringBefore(" ").substringBefore(")") == "0"
                    continue
                }
                if (inDisplay0 && t.startsWith("mCurrentFocus=")) c.add(t)
                if (inDisplay0 && t.startsWith("mFocusedApp=")) a.add(t)
            }
            cur = c; app = a
        } else {
            cur = raw.lineSequence().filter { it.contains("mCurrentFocus") }.toList()
            app = raw.lineSequence().filter { it.contains("mFocusedApp") }.toList()
        }
        val ok = cur.isNotEmpty() && cur.all { mentions(it) } && app.all { mentions(it) }
        val joined = (cur + app).joinToString(" | ").replace('\n', ' ').take(300)
        return (if (joined.isNotEmpty()) joined else raw.replace('\n', ' ').take(300)) to ok
    }

    /** Distinct-color count over a coarse grid: a uniform (black/frozen)
     *  screen is NOT desktop evidence. */
    /** Result of a one-shot guest command. code=-1 on timeout; leftover =
     *  pids from THIS invocation's subtree still alive after reap. Cleanup
     *  after a TIMEOUT is best-effort only: an orphaned tracee can be
     *  reparented out of the captured subtree and escape verification, so a
     *  timeout aborts the whole probe (guestAborted) - no further guest
     *  work runs on top of possible helpers. */
    private data class GuestOut(val code: Int, val text: String, val leftover: List<Int>)

    /** Latched by guestExec on the FIRST timeout OR unverifiable cleanup:
     *  the probe fails closed and no further guest commands run (unresolved
     *  state is reported, never buried under more helper processes). */
    private var guestAborted = false

    /** Direct children of THIS process via /proc ppid walk (same parsing as
     *  pidTreeSnapshot), pid -> starttime for ownership anchoring.
     *  NULL = the capture is incomplete or unreadable (UNKNOWN): a silent
     *  partial capture could hide the just-launched helper, so callers
     *  fail closed. ENOENT between list and read = the process exited -
     *  not unknown. */
    private fun selfChildren(): Map<Int, String>? {
        val me = android.os.Process.myPid()
        val out = mutableMapOf<Int, String>()
        val dirs = File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) }
            ?: return null
        for (f in dirs) {
            val pid = f.name.toInt()
            val (content, errno) = readProcStat(pid)
            if (content == null) {
                if (errno == android.system.OsConstants.ENOENT) continue
                return null
            }
            val close = content.lastIndexOf(')')
            val rest = if (close >= 0) content.substring(close + 1).trim().split(Regex("\\s+")) else return null
            if (rest.size <= 20) return null
            if (rest[1].toIntOrNull() == me) out[pid] = rest[19]
        }
        return out
    }

    /** Run one command inside the Ubuntu rootfs against the SAME X display
     *  (:0 socket bind identical to the session launch). Evidence collection
     *  only: no --kill-on-exit; cleanup is VERIFIED per call. */
    private fun guestExec(rootfs: File, timeoutMs: Long, vararg args: String): GuestOut {
        val nd = ctx.applicationInfo.nativeLibraryDir
        val pb = ProcessBuilder(mutableListOf(
            File(nd, "libproot.so").absolutePath,
            "--link2symlink", "-0", "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", XServer.socketDir(ctx).absolutePath + ":/tmp/.X11-unix",
            "-w", "/root") + args.toList())
        pb.environment().putAll(mapOf(
            "PROOT_LOADER" to File(nd, "libproot_loader.so").absolutePath,
            "PROOT_LOADER_32" to File(nd, "libproot_loader32.so").absolutePath,
            "LD_LIBRARY_PATH" to nd,
            "HOME" to "/root", "DISPLAY" to ":0",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_TMP_DIR" to Proot.prootTmpDir(ctx).absolutePath,
            "TMPDIR" to "/tmp"))
        pb.redirectErrorStream(true)
        // Baseline capture BEFORE start; a failed baseline means the later
        // diff can never be trusted - abort before any process exists.
        val preKids = selfChildren()
            ?: throw AssertionError("render-evidence: pre-spawn child capture UNREADABLE -" +
                " refusing to launch an untrackable helper")
        val p = pb.start()
        // Post-spawn capture: on ANY uncertainty the just-launched process
        // gets a bounded stop/reap ladder BEFORE the failure propagates -
        // never thrown and abandoned.
        val postKids = selfChildren()
        if (postKids == null) {
            p.destroy()
            if (!p.waitFor(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly(); p.waitFor(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            guestAborted = true
            throw AssertionError("render-evidence: post-spawn child capture UNREADABLE -" +
                " helper stopped/reaped best-effort (destroy ladder), probe aborted")
        }
        var helperKids = postKids - preKids.keys
        var completedEarlyCode: Int? = null
        if (helperKids.isEmpty()) {
            // Empty diff is only meaningful if the helper ALREADY exited
            // and was reaped (exitValue returns) - an alive but uncaptured
            // helper is UNKNOWN: bounded stop/reap, latch abort.
            try {
                completedEarlyCode = p.exitValue()
            } catch (alive: IllegalThreadStateException) {
                p.destroy()
                if (!p.waitFor(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly(); p.waitFor(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                guestAborted = true
                throw AssertionError("render-evidence: live helper NOT captured in post-spawn" +
                    " scan - stopped/reaped best-effort, probe aborted")
            }
        }
        val out = StringBuilder()
        val drain = kotlin.concurrent.thread {
            try { p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } } catch (_: Throwable) {}
        }
        val code: Int
        if (completedEarlyCode != null) {
            code = completedEarlyCode
        } else if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroy()
            if (!p.waitFor(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly(); p.waitFor(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            code = -1
            guestAborted = true
        } else code = p.exitValue()
        drain.join(2_000)
        // waitFor only proves the DIRECT child exited; the helper subtree
        // (proot root + tracee descendants) must be GONE. Every check is
        // errno-aware: ENOENT = gone; any other read error = UNKNOWN, which
        // latches the probe abort (cleanup not verifiable) instead of
        // claiming a verified-absent subtree. A live pid whose starttime
        // differs from the captured one is a REUSED pid, not our helper.
        val leftover = mutableListOf<Int>()
        var unknown = false
        val stats = HashMap<Int, Pair<Int, String>>() // pid -> (ppid, starttime)
        val procDirs = File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) }
        if (procDirs == null) {
            guestAborted = true
            return GuestOut(code, out.toString(), leftover)
        }
        procDirs.forEach { f ->
            val pid = f.name.toInt()
            val (content, errno) = readProcStat(pid)
            if (content == null) {
                if (errno != android.system.OsConstants.ENOENT) unknown = true
                return@forEach
            }
            val close = content.lastIndexOf(')')
            val rest = if (close >= 0) content.substring(close + 1).trim().split(Regex("\\s+")) else emptyList()
            val ppid = if (rest.size > 20) rest[1].toIntOrNull() else null
            if (ppid != null) stats[pid] = ppid to rest[19]
            else { unknown = true; return@forEach }
        }
        var frontier = helperKids.keys.toList()
        val seen = mutableSetOf<Int>()
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<Int>()
            for ((pid, ps) in stats) {
                if (ps.first in frontier && pid !in seen) { seen.add(pid); next.add(pid) }
            }
            frontier = next
        }
        for (k in helperKids.keys) {
            if (stats[k]?.second != null && stats[k]?.second == helperKids[k]) leftover.add(k)
        }
        for (d in seen) {
            if (d in helperKids.keys) continue
            if (stats[d]?.second != null) leftover.add(d)
        }
        if (unknown) guestAborted = true
        return GuestOut(code, out.toString(), leftover)
    }

    private fun requireGuest(out: GuestOut, what: String): String {
        if (out.code == -1) throw AssertionError("render-evidence: guest [$what] TIMED OUT;" +
            " cleanup UNRESOLVED (best-effort kill ladder done, leftover=${out.leftover});" +
            " probe aborted - no further guest work")
        if (guestAborted) throw AssertionError("render-evidence: guest [$what] cleanup" +
            " UNVERIFIABLE (/proc scan error) - probe aborted, no further guest work")
        assertTrue("render-evidence: guest [$what] cleanup verified (leftover=${out.leftover})",
            out.leftover.isEmpty())
        assertTrue("render-evidence: guest [$what] exit=0 (was ${out.code}): ${out.text.take(200)}",
            out.code == 0)
        return out.text
    }

    /** /proc/<pid>/<file> read with identity re-verification around the
     *  read: the pid must carry the SNAPSHOT starttime before AND after,
     *  else the read is not of the tracked process - fail closed (null). */
    private fun anchoredRead(pid: Int, snapshotStart: String, file: String): String? {
        if (procStartTime(pid) != snapshotStart) return null
        val bytes = try { File("/proc/$pid/$file").readBytes() } catch (t: Throwable) { return null }
        if (procStartTime(pid) != snapshotStart) return null
        return String(bytes, Charsets.ISO_8859_1)
    }

    /** Save THE ANALYZED bitmap (never a second capture instant) through
     *  the proven PHONESHOT logcat channel (same chunking as Shot). */
    private fun saveAnalyzedShot(name: String, bmp: Bitmap) {
        val w = 720; val h = bmp.height * w / bmp.width
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        val baos = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos)
        val bytes = baos.toByteArray()
        assertTrue("analyzed shot $name encoded non-empty", bytes.isNotEmpty())
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val chunk = 3000
        val total = (b64.length + chunk - 1) / chunk
        var seq = 0
        while (seq < total) {
            val end = minOf((seq + 1) * chunk, b64.length)
            android.util.Log.i("PHONESHOT", name + "|" + String.format("%04d|%04d|", seq, total) +
                b64.substring(seq * chunk, end))
            seq++
        }
        android.util.Log.i("PHONESHOT", name + " exported: " + bytes.size + " bytes, " + total + " chunks")
    }

    /** Render qualification evidence for the production-pair run. Proves
     *  SessionConfig is actually CONSUMED by this session (profile on disk,
     *  process args/environment, all scoped to the tracked pid+starttime
     *  tree), then captures the UNCOVERED root via a non-destructive
     *  iconify+restore with identity preserved. Geometry (window tree,
     *  coverage, occluders) SUPPORTS, never replaces, the pixels. Tray
     *  pixels are never asserted: the tray is a separate painter and can
     *  never prove the pcmanfm desktop background. The "modules directory
     *  is not accessible" warning is logged as a DIAGNOSTIC only. */
    private fun renderQualification(rootPid: Int, openTapAt: Long, launchRootStart: String) {
        val rootfs = DistroInstaller(ctx).rootfsDir(Distros.UBUNTU)
        val deadlineEnd = System.nanoTime() + 120_000_000_000L
        fun rem(): Long {
            val r = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineEnd - System.nanoTime())
            if (r <= 0) throw AssertionError("render/probe: overall deadline exhausted")
            return r
        }
        fun guest(vararg a: String): String {
            if (guestAborted) throw AssertionError("render-evidence: probe already aborted by an" +
                " earlier guest timeout - refusing further guest work")
            return requireGuest(guestExec(rootfs, rem(), *a), a.joinToString(" "))
        }
        fun guestR(vararg a: String): String {
            if (guestAborted) throw AssertionError("render-evidence: probe already aborted by an" +
                " earlier guest timeout - refusing further guest work")
            return requireGuest(guestExec(rootfs, 15_000, *a), a.joinToString(" "))
        }

        // 0. Persisted-choice state. CORRECT store: MobileModeSettings reads
        //    "plinux_mobile" ("plinux_desktop" is the seam/marker store).
        //    A fresh CI emulator carries no choices -> DEFAULTS path only.
        val persisted = ctx.getSharedPreferences("plinux_mobile", Context.MODE_PRIVATE)
            .all.keys.sorted()
        Log.i(TAG, "render-evidence: plinux_mobile persisted keys=" + persisted +
            " (empty = fresh emulator, defaults path; explicit saved-settings path NOT" +
            " exercised at runtime - unit contract: MobileModeSettingsTest)")
        val ui = MobileModeSettings.read(ctx)

        // 1. Tracked-tree process identity: COMPLETE snapshot or fail.
        val unread = mutableMapOf<Int, String>()
        val tree = pidTreeSnapshot(rootPid, unread)
        assertTrue("render/process: tracked tree capture complete (unreadable=$unread)",
            unread.isEmpty())
        // The snapshot root must BE the original launch anchor, before and
        // after the snapshot - never a fresh recapture as authority.
        assertEquals("render/process: snapshot root identity is the launch anchor",
            launchRootStart, tree[rootPid])
        assertEquals("render/process: root identity stable across the snapshot",
            launchRootStart, procStartTime(rootPid))
        var jwmPid = -1; var pcmanPid = -1; var xtermPid = -1; var shellPid = -1
        var jwmCmd = ""
        for ((pid, st) in tree) {
            val cmd = anchoredRead(pid, st, "cmdline")?.replace(0.toChar(), ' ')?.trim()
                ?: throw AssertionError("render/process: tracked member $pid unreadable or" +
                    " identity-churned during classification - UNKNOWN, never treated as absent")
            when {
                cmd.startsWith("jwm -f ") -> {
                    assertTrue("render/process: ambiguous jwm in tree", jwmPid < 0)
                    jwmPid = pid; jwmCmd = cmd
                }
                cmd == "pcmanfm --desktop" -> {
                    assertTrue("render/process: ambiguous pcmanfm in tree", pcmanPid < 0)
                    pcmanPid = pid
                }
                cmd.startsWith("xterm ") -> {
                    assertTrue("render/process: ambiguous xterm in tree", xtermPid < 0)
                    xtermPid = pid
                }
                cmd.startsWith("/bin/sh /root/.config-ldoa/") -> {
                    assertTrue("render/process: ambiguous session shell in tree", shellPid < 0)
                    shellPid = pid
                }
            }
        }
        assertTrue("render/process: jwm found in the tracked tree", jwmPid > 0)
        assertTrue("render/process: pcmanfm found in the tracked tree", pcmanPid > 0)
        assertTrue("render/process: xterm found in the tracked tree", xtermPid > 0)
        assertTrue("render/process: session shell found in the tracked tree", shellPid > 0)

        // 2. Session dir: identity comes from the RUNNING jwm's -f argument;
        //    the embedded millis is corroboration, never the authority.
        val sessName = Regex("^jwm -f /root/\\.config-ldoa/(session-[0-9]+-[0-9a-f]{1,4})/jwmrc$")
            .find(jwmCmd)?.groupValues?.get(1)
        assertNotNull("render/process: jwm -f consumes a session-managed jwmrc (was: $jwmCmd)", sessName)
        val sess = File(rootfs, "root/.config-ldoa/" + sessName)
        assertTrue("render/session-profile: dir $sessName exists on disk", sess.isDirectory)
        val sessMillis = sessName!!.removePrefix("session-").substringBefore('-').toLongOrNull() ?: 0L
        assertTrue("render/session-profile: $sessName created by THIS launch" +
            " (millis=$sessMillis vs open tap at $openTapAt)", sessMillis >= openTapAt - 5_000)

        // 3. Environment consumption, identity-anchored, selected keys only.
        val shellEnv = anchoredRead(shellPid, tree.getValue(shellPid), "environ")
        val sessionCfg = shellEnv?.split(0.toChar())
            ?.firstOrNull { it.startsWith("SESSION_CFG=") }?.substringAfter('=')
        assertEquals("render/process: session shell SESSION_CFG is THIS session's dir",
            "/root/.config-ldoa/$sessName", sessionCfg)
        val pcmanEnv = anchoredRead(pcmanPid, tree.getValue(pcmanPid), "environ")
        val xdg = pcmanEnv?.split(0.toChar())
            ?.firstOrNull { it.startsWith("XDG_CONFIG_HOME=") }?.substringAfter('=')
        Log.i(TAG, "render-evidence: pcmanfm[$pcmanPid] XDG_CONFIG_HOME=$xdg (only this key read)")
        assertEquals("render/process: pcmanfm XDG_CONFIG_HOME is THIS session's profile dir",
            "/root/.config-ldoa/$sessName", xdg)

        // 4. Generated profile on disk.
        assertTrue("render/session-profile: mobile-session.sh present",
            File(sess, "mobile-session.sh").isFile)
        val jwmrc = File(sess, "jwmrc")
        assertTrue("render/session-profile: jwmrc present and non-empty",
            jwmrc.isFile && jwmrc.length() > 0)
        if (ui.panelPosition == "top") {
            assertTrue("render/session-profile: jwmrc carries the configured top tray",
                jwmrc.readText().contains("<Tray x=\"0\" y=\"0\""))
        }
        assertTrue("render/session-profile: Xresources carries Xft.dpi",
            File(sess, "Xresources").readText().contains("Xft.dpi:"))
        val desktopItems = File(sess, "pcmanfm/default/desktop-items-0.conf")
        assertTrue("render/session-profile: pcmanfm profile present", desktopItems.isFile)
        val di = desktopItems.readText()
        assertTrue("render/session-profile: wallpaper_mode=color",
            di.contains("wallpaper_mode=color"))
        assertTrue("render/session-profile: desktop_bg is the configured ${ui.background}",
            di.contains("desktop_bg=${ui.background}"))
        Log.i(TAG, "render-evidence: session profile $sessName verified on disk")

        // 5. Guest tooling gate (provisioning gap fails distinctly).
        for (tool in listOf("xdotool", "xwininfo", "xprop", "xrandr", "xdpyinfo"))
            assertTrue("render tooling: guest $tool present (provisioning gap if not)",
                File(rootfs, "usr/bin/$tool").isFile)

        // 6. Geometry ground truth: POSITIVE root dims, parser-verified.
        //    (early sanity only; the BINDING root stats come from the
        //    settled sample in step 7 - the root can resize during startup)
        val rootStats = RenderProbeParse.winStats(guest("xwininfo", "-root"))
        assertNotNull("render/geometry: root stats parseable with positive dims", rootStats)

        // 7. Desktop window identity, TOPOLOGY-ANCHORED + BOUNDED SETTLE
        //    SAMPLER. OBSERVED (run 41532775447): the qualified desktop was
        //    1280x1949 while the settled screen/monitor was 1080x1949.
        //    HYPOTHESIS (unproven): the desktop was realized at an
        //    intermediate startup geometry and the final change never
        //    reached an applied resize - pcmanfm 1.3.2 resizes its desktop
        //    only on GdkScreen "monitors-changed" (desktop.c:4977) and the
        //    source shows the INTENDED notification path, never runtime
        //    delivery/consumption. The sampler records the ACTUAL geometry
        //    trajectory - every observation fully rescanned/revalidated,
        //    raw, wall+mono timestamped - and gates on STABILITY: 3
        //    CONSECUTIVE observations that each pass the SAME exact gates
        //    on their own current topology AND are EQUAL on the full
        //    stable key (owner pid+starttime, desktop XID, rects, primary/
        //    intended selection, root geometry). Fail-closed: an unknown
        //    (unparseable/unreadable) observation fails IMMEDIATELY; a
        //    probe returning after the subdeadline ALWAYS fails; deadline
        //    without stability fails UNCONDITIONALLY - a final lucky frame
        //    never substitutes for stability. The settle delay itself is
        //    logged as a product finding, not erased.
        val pcmanStart0 = tree.getValue(pcmanPid)  // ORIGINAL ownership anchor
        if (procStartTime(pcmanPid) != pcmanStart0)
            throw AssertionError("render/geometry: pcmanfm process identity CHANGED before" +
                " sampling (restart/PID reuse) - UNKNOWN, never accepting a new process" +
                " under the owned pid")
        val settle = RenderProbeParse.SettleBudget(System.nanoTime(), deadlineEnd,
            20_000_000_000L, 5_000_000_000L)
        if (settle.remainingMs(System.nanoTime()) <= 0)
            throw AssertionError("render/geometry: no budget left for settle sampling" +
                " (global deadline) - UNKNOWN, never skipped")

        fun settleGuest(vararg a: String): String {
            if (guestAborted) throw AssertionError("render-evidence: probe already aborted" +
                " by an earlier guest timeout - refusing further guest work")
            val remMs = minOf(rem(), settle.remainingMs(System.nanoTime()))
            if (remMs <= 0) throw AssertionError("render/geometry: settle budget EXHAUSTED" +
                " before probe " + a.joinToString(" ") + " - UNKNOWN, never issuing an" +
                " unbounded call")
            // Per-call cap = remaining budget; the existing guestExec
            // timeout + destroy-ladder cleanup path is REUSED unchanged.
            val out = requireGuest(guestExec(rootfs, remMs, *a), a.joinToString(" "))
            settle.lateReason(System.nanoTime())?.let {
                throw AssertionError("render/geometry: probe " + a.joinToString(" ") +
                    " returned AFTER the settle subdeadline (" + it + ") - return-late" +
                    " ALWAYS fails")
            }
            return out
        }

        data class Sample(val atNanos: Long, val doneNanos: Long, val wallMs: Long,
                          val reason: String?, val state: RenderProbeParse.TopoState?,
                          val ds: RenderProbeParse.WinStats?, val rootSt: RenderProbeParse.WinStats?,
                          val selMon: IntArray?, val rrRaw: String, val xinDesc: String,
                          val monRects: List<IntArray>, val deskRects: List<IntArray>,
                          val rootRect: IntArray?, val identities: String)

        fun takeSample(): Sample {
            val t0 = System.nanoTime()
            val wall = System.currentTimeMillis()
            // Fresh EVERYTHING each observation - no stale XID chain.
            val rrRawS = settleGuest("xrandr", "--listmonitors")
            Log.i(TAG, "render-evidence: sample xrandr --listmonitors RAW:\n" + rrRawS)
            val rrM = RenderProbeParse.xrandrListMonitors(rrRawS)
                ?: throw AssertionError("render/geometry: sampler xrandr UNPARSEABLE -" +
                    " unknown, never guessed; RAW:\n" + rrRawS)
            val rrAct = rrM.filter { it.active }
            val mons = rrAct.map { intArrayOf(it.x, it.y, it.w, it.h) }
            val xinRawS = settleGuest("xdpyinfo", "-ext", "XINERAMA")
            Log.i(TAG, "render-evidence: sample xdpyinfo -ext XINERAMA RAW:\n" + xinRawS)
            val xinS = RenderProbeParse.xdpyinfoXinerama(xinRawS)
                ?: throw AssertionError("render/geometry: sampler xdpyinfo XINERAMA section" +
                    " MALFORMED - declared-but-unparseable topology NEVER falls back; RAW:\n" + xinRawS)
            var xinIssue: String? = null
            if (xinS.state == RenderProbeParse.XineramaState.ACTIVE && xinS.heads.isNotEmpty() &&
                (xinS.heads.size != mons.size ||
                    RenderProbeParse.matchMonitorBijection(xinS.heads, mons) == null))
                xinIssue = "topology-sources-disagree"
            val rootStS = RenderProbeParse.winStats(settleGuest("xwininfo", "-root"))
                ?: throw AssertionError("render/geometry: sampler root stats UNREADABLE - unknown")
            val rootR = intArrayOf(rootStS.absX, rootStS.absY, rootStS.w, rootStS.h)
            // Candidate identity re-probe (the same verified chain); RAW
            // xprop+xwininfo (incl. the window NAME line) logged for EVERY
            // candidate IMMEDIATELY, before parsing - the evidence exists
            // whichever way the sample fails.
            val pcmanCandS = (RenderProbeParse.searchIds(settleGuest("xdotool", "search", "--class", "pcmanfm"))
                ?: throw AssertionError("render/geometry: sampler pcmanfm search unparseable")) +
                (RenderProbeParse.searchIds(settleGuest("xdotool", "search", "--class", "Pcmanfm"))
                ?: throw AssertionError("render/geometry: sampler Pcmanfm search unparseable"))
            val qual = mutableListOf<Pair<Long, RenderProbeParse.WinStats>>()
            val ids = StringBuilder()
            for (id in pcmanCandS.distinct()) {
                val xp = settleGuest("xprop", "-id", id.toString(),
                    "_NET_WM_PID", "WM_CLASS", "_NET_WM_WINDOW_TYPE")
                Log.i(TAG, "render-evidence: candidate 0x" + java.lang.Long.toHexString(id) +
                    " xprop RAW:\n" + xp)
                val xw = settleGuest("xwininfo", "-id", id.toString())
                Log.i(TAG, "render-evidence: candidate 0x" + java.lang.Long.toHexString(id) +
                    " xwininfo RAW (incl. NAME):\n" + xw)
                val pid = RenderProbeParse.xpropPid(xp)
                val pidAbsent = RenderProbeParse.xpropPidAbsent(xp)
                val cls = RenderProbeParse.xpropClass(xp)
                val types = RenderProbeParse.xpropWmTypes(xp)
                val st = RenderProbeParse.winStats(xw)
                ids.append("candidate 0x").append(java.lang.Long.toHexString(id))
                    .append(" pid=").append(pid?.toString() ?: if (pidAbsent) "ABSENT" else "UNREADABLE")
                    .append(" class=").append(cls)
                    .append(" types=").append(types?.toString() ?: "UNREADABLE")
                    .append(" stats=").append(st?.toString() ?: "UNREADABLE").append('\n')
                if ((pid == null && !pidAbsent) || cls == null || types == null || st == null)
                    throw AssertionError("render/geometry: sampler candidate 0x" +
                        java.lang.Long.toHexString(id) + " probe UNREADABLE - unknown," +
                        " never skipped; identities so far:\n" + ids)
                if (pid == null) continue  // explicitly ABSENT: not owned, documented
                if (pid.toInt() == pcmanPid && cls.second == "Pcmanfm" &&
                    types.contains("_NET_WM_WINDOW_TYPE_DESKTOP") && st.mapState == "IsViewable")
                    qual.add(id to st)
            }
            // Owner process identity: fresh anchor compared to the ORIGINAL
            // ownership anchor - a changed starttime = restart/PID reuse,
            // never "a new process under the old pid is fine".
            val pStart = procStartTime(pcmanPid)
                ?: throw AssertionError("render/geometry: sampler pcmanfm process anchor" +
                    " unreadable - unknown, never assumed")
            if (pStart != pcmanStart0)
                throw AssertionError("render/geometry: pcmanfm process identity CHANGED" +
                    " mid-sampling (" + pcmanStart0 + " -> " + pStart + ") - restart/PID" +
                    " reuse; UNKNOWN, never stabilizing an unrelated process")
            settle.lateReason(System.nanoTime())?.let {
                throw AssertionError("render/geometry: process-anchor read completed AFTER" +
                    " the settle subdeadline (" + it + ") - return-late ALWAYS fails")
            }
            val primCount = rrAct.count { it.primary }
            val dRects = qual.map { intArrayOf(it.second.absX, it.second.absY, it.second.w, it.second.h) }
            val reason = xinIssue ?: RenderProbeParse.topologyConsistency(rootR, mons, primCount, dRects)
            if (reason != null)
                return Sample(t0, System.nanoTime(), wall, reason, null, null, rootStS, null,
                    rrRawS, xinS.state.toString() + "(heads=" + xinS.heads.size + ")",
                    mons, dRects, rootR, ids.toString())
            // Consistent: the INTENDED selection (primary's desktop, or sole).
            val bij = RenderProbeParse.matchMonitorBijection(dRects, mons)!!
            val selIdx = if (qual.size == 1) 0
                else bij.indexOf(rrAct.withIndex().first { it.value.primary }.index)
            val deskIdS = qual[selIdx].first
            val dsS = qual[selIdx].second
            val monsSorted = mons.sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] }, { it[3] }))
            val dSorted = dRects.sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] }, { it[3] }))
            val primRect = rrAct.firstOrNull { it.primary }
                ?.let { intArrayOf(it.x, it.y, it.w, it.h) }
            val st = RenderProbeParse.TopoState(pcmanPid, pStart, deskIdS,
                dRects[selIdx].copyOf(), dSorted, monsSorted,
                monsSorted.indexOfFirst { primRect != null && it.contentEquals(primRect) },
                rootR.copyOf())
            return Sample(t0, System.nanoTime(), wall, null, st, dsS, rootStS,
                mons[bij[selIdx]].copyOf(), rrRawS,
                xinS.state.toString() + "(heads=" + xinS.heads.size + ")",
                mons, dRects, rootR, ids.toString())
        }

        var lastSample: Sample? = null
        val trajectory = StringBuilder()
        var firstSampleNanos = -1L
        var firstConsistentNanos = -1L
        val settled = try {
            RenderProbeParse.settleLoop(
                settle, 3, 500L,
                now = { System.nanoTime() },
                sleep = { ms -> Thread.sleep(ms) },
                sample = {
                    val s = takeSample()
                    lastSample = s
                    RenderProbeParse.SettleObservation(s.reason, s.state, s.doneNanos)
                },
                onSample = { idx, _ ->
                    val s = lastSample!!
                    if (firstSampleNanos < 0) firstSampleNanos = s.atNanos
                    if (s.reason == null && firstConsistentNanos < 0) firstConsistentNanos = s.doneNanos
                    trajectory.append("#").append(idx)
                        .append(" wall=").append(s.wallMs)
                        .append(" mono+").append((s.doneNanos - firstSampleNanos) / 1_000_000).append("ms")
                        .append(" round=").append((s.doneNanos - s.atNanos) / 1_000_000).append("ms")
                        .append(" ").append(s.reason ?: "CONSISTENT")
                        .append(" root=").append(s.rootRect?.contentToString() ?: "-")
                        .append(" mons=").append(s.monRects.joinToString { it.contentToString() })
                        .append(" desks=").append(s.deskRects.joinToString { it.contentToString() })
                        .append('\n')
                    Log.i(TAG, "render-evidence: settle sample #" + idx + " wall=" + s.wallMs +
                        " mono+" + (s.doneNanos - firstSampleNanos) / 1_000_000 + "ms " +
                        (s.reason ?: "CONSISTENT") +
                        (if (s.identities.isNotEmpty()) "\n" + s.identities else ""))
                })
        } catch (e: AssertionError) {
            // SettleFailure AND any unknown from inside takeSample - the
            // trajectory is attached either way (raw candidate/sample logs
            // remain in logcat regardless).
            throw AssertionError("render/geometry: " + e.message + "\nsettle trajectory:\n" + trajectory)
        }
        // lastSample is the post-stability REVALIDATION sample - key-equal
        // to the stable observation (settleLoop threw otherwise) and the
        // most current full state, so downstream checks build on it.
        val stable = lastSample!!
        Log.i(TAG, "render-evidence: topology SETTLED (product finding: first consistent" +
            " at +" + (if (firstConsistentNanos >= 0) (firstConsistentNanos - firstSampleNanos) / 1_000_000 else -1) +
            "ms, stable at +" + (settled.doneNanos - firstSampleNanos) / 1_000_000 +
            "ms, revalidated at +" + (stable.doneNanos - firstSampleNanos) / 1_000_000 +
            "ms of observation)")
        val deskId = stable.state!!.deskId
        val ds = stable.ds!!
        val selMon = stable.selMon!!
        val rs = stable.rootSt!!
        val enabledMonitors = stable.state.monRects.size
        Log.i(TAG, "render-evidence: desktop window " + ds + " selected on monitor " +
            selMon.contentToString() + " over root " + rs.w + "x" + rs.h +
            " (monitors=" + enabledMonitors + ", stable-key pid=" + stable.state.pcmanPid +
            "/" + stable.state.pcmanStart + " xid=0x" + java.lang.Long.toHexString(deskId) + ")")

        // 8. xterm window identity via a VERIFIED channel - never a class
        //    or uniqueness heuristic: xterm sets WINDOWID (the X window id
        //    of the xterm window, xterm(1) ENVIRONMENT) in the environment
        //    of the shell it spawns. Chain: tracked tree -> xterm's direct
        //    child (ppid-anchored) -> its environ WINDOWID -> THE window.
        //    noble's xterm (390) does NOT set _NET_WM_PID on its own window
        //    (source-verified: main.c names the atom only in a list), so
        //    _NET_WM_PID is not usable here. Absent/ambiguous linkage fails
        //    closed - there is no fallback identity.
        var xtId = -1L
        var xtChildPid = -1
        run {
            val linked = mutableListOf<Pair<Int, Long>>()
            for ((pid, st) in tree) {
                if (pid == xtermPid) continue
                val stat = anchoredRead(pid, st, "stat")
                    ?: throw AssertionError("render/process: tracked member $pid unreadable or" +
                        " identity-churned during linkage - UNKNOWN, never treated as absent")
                val close = stat.lastIndexOf(')')
                val rest = if (close >= 0) stat.substring(close + 1).trim()
                    .split(Regex("\\s+")) else continue
                if (rest.size <= 1 || rest[1].toIntOrNull() != xtermPid) continue
                val env = anchoredRead(pid, st, "environ")
                    ?: throw AssertionError("render/process: xterm child $pid environ unreadable" +
                        " or identity-churned - UNKNOWN, never treated as absent")
                val wid = env.split(0.toChar())
                    .firstOrNull { it.startsWith("WINDOWID=") }?.substringAfter('=')
                    ?.toLongOrNull() ?: continue
                linked.add(pid to wid)
            }
            assertTrue("render/geometry: exactly one xterm child carries WINDOWID" +
                " (found ${linked.size} - absent/ambiguous linkage fails closed, no fallback)",
                linked.size == 1)
            xtChildPid = linked[0].first
            xtId = linked[0].second
        }
        val xtProp = guest("xprop", "-id", xtId.toString(), "WM_CLASS")
        assertEquals("render/geometry: WINDOWID-linked window is XTerm class", "XTerm",
            RenderProbeParse.xpropClass(xtProp)?.second)
        // Consistency, not identity: the XTerm-class window SET must be
        // exactly the linked window - any other XTerm-class client makes the
        // session shape ambiguous and fails the probe.
        val xtCandidates = RenderProbeParse.searchIds(guest("xdotool", "search", "--class", "XTerm"))
            ?: throw AssertionError("render/geometry: XTerm search output unparseable")
        assertEquals("render/geometry: XTerm-class window set is exactly the linked window",
            listOf(xtId), xtCandidates.sorted())
        val xtStats = RenderProbeParse.winStats(guest("xwininfo", "-id", xtId.toString()))
        assertEquals("render/uncover: xterm viewable before iconify", "IsViewable", xtStats?.mapState)
        val xtStart = tree.getValue(xtermPid)

        // 9. Uncovered root: ROI = central band of the desktop window in
        //    X coords (avoids the tray strip and the extra-keys bar).
        val roiX = ds.absX + (ds.w * 0.2).toInt()
        val roiY = ds.absY + (ds.h * 0.35).toInt()
        val roiW = (ds.w * 0.6).toInt()
        val roiH = (ds.h * 0.3).toInt()

        assertTrue("render/geometry: ROI degenerate (non-positive extent)",
            roiW > 0 && roiH > 0)
        assertTrue("render/geometry: ROI [" + roiX + "," + roiY + " " + roiW + "x" + roiH +
            "] not INSIDE the selected monitor " + selMon.contentToString() +
            " - sample region must lie in the actual intended display",
            roiX >= selMon[0] && roiY >= selMon[1] &&
            roiX + roiW <= selMon[0] + selMon[2] && roiY + roiH <= selMon[1] + selMon[3])

        var primary: Throwable? = null
        try {
            // Restore obligation is armed BEFORE the minimize attempt: a
            // command can minimize and still exit nonzero. Anchors are
            // rechecked immediately before the action, not only after
            // the restore.
            assertEquals("render/uncover: xterm anchor unchanged before minimize",
                xtStart, procStartTime(xtermPid))
            assertEquals("render/uncover: xterm child anchor unchanged before minimize",
                tree.getValue(xtChildPid), procStartTime(xtChildPid))
            guest("xdotool", "windowminimize", xtId.toString())
            var mst = RenderProbeParse.winStats(guest("xwininfo", "-id", xtId.toString()))
            while (mst?.mapState != "IsUnMapped" && System.nanoTime() < deadlineEnd) {
                Thread.sleep(500)
                mst = RenderProbeParse.winStats(guest("xwininfo", "-id", xtId.toString()))
            }
            assertEquals("render/uncover: xterm iconified within budget", "IsUnMapped", mst?.mapState)
            Thread.sleep(1_000) // paint settle after unmap

            // No OTHER MAPPED top-level window may intersect the sample ROI.
            val kids = RenderProbeParse.topLevelChildren(guest("xwininfo", "-root", "-children"))
            assertNotNull("render/geometry: root children listing parseable", kids)
            val occluders = mutableListOf<String>()
            for (kid in kids!!) {
                if (kid.id == deskId) continue
                val ks = RenderProbeParse.winStats(guest("xwininfo", "-id", kid.id.toString()))
                    ?: throw AssertionError("render/geometry: top-level window 0x" +
                        java.lang.Long.toHexString(kid.id) + " stats UNPARSEABLE - unknown," +
                        " never skipped (a potentially covering window could be hidden)")
                if (ks.mapState == "IsViewable" && ks.overlaps(roiX, roiY, roiW, roiH))
                    occluders.add("0x" + java.lang.Long.toHexString(kid.id) + " " + ks)
            }
            assertTrue("render/geometry: no other mapped window intersects the sample ROI:" +
                " $occluders", occluders.isEmpty())

            // X->screenshot transform from the ACTUAL lorie view bounds;
            // ambiguous mapping fails, never silently sampled.
            val lv = device.findObject(By.res("dev.phonelinux.proto:id/lorieView"))
            assertNotNull("render/roi: lorie view present", lv)
            val vb = lv!!.visibleBounds
            val bmp0 = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            assertNotNull("render/uncover screenshot", bmp0)
            val bmp = bmp0!!
            try {
                val sx = vb.width().toFloat() / rs.w
                val sy = vb.height().toFloat() / rs.h
                assertTrue("render/roi: surface bounds plausible" +
                    " (${vb.width()}x${vb.height()} vs bitmap ${bmp.width}x${bmp.height})",
                    vb.width() >= bmp.width / 2)
                assertTrue("render/roi: uniform X->screen scaling (sx=$sx sy=$sy)",
                    kotlin.math.abs(sx / sy - 1f) <= 0.03f)
                val mapped = RenderProbeParse.mapRect(roiX, roiY, roiW, roiH, sx, sy,
                    vb.left, vb.top, bmp.width, bmp.height)
                assertNotNull("render/roi: mapped ROI must be positive and ENTIRELY inside" +
                    " the bitmap (no clamping, no vacuous pass)", mapped)
                val bl = mapped!![0]; val bt = mapped[1]; val br = mapped[2]; val bb = mapped[3]
                // The decisive bitmap is archived BEFORE the color asserts:
                // a failing sample keeps its own evidence.
                saveAnalyzedShot("m1-render-uncovered-root", bmp)
                val exp = android.graphics.Color.parseColor(ui.background)
                val er = android.graphics.Color.red(exp)
                val eg = android.graphics.Color.green(exp)
                val eb = android.graphics.Color.blue(exp)
                var minMaxChannel = 255; var maxDev = 0; val cols = HashSet<Int>()
                var py = bt
                while (py < bb) {
                    var px = bl
                    while (px < br) {
                        val c = bmp.getPixel(px, py)
                        cols.add(c)
                        val r = android.graphics.Color.red(c)
                        val g = android.graphics.Color.green(c)
                        val bc = android.graphics.Color.blue(c)
                        maxDev = maxOf(maxDev, kotlin.math.abs(r - er),
                            kotlin.math.abs(g - eg), kotlin.math.abs(bc - eb))
                        minMaxChannel = minOf(minMaxChannel, maxOf(r, g, bc))
                        px += 32
                    }
                    py += 32
                }
                Log.i(TAG, "render-evidence: ROI bitmap=[$bl,$bt..$br,$bb] distinct=" + cols.size +
                    " maxDev=" + maxDev + " minMaxChannel=" + minMaxChannel + " expected=" +
                    ui.background + " (RAW bitmap analysis; JPEG applies only to the artifact)")
                assertTrue("render/desktop-color: uncovered desktop ROI matches the configured" +
                    " ${ui.background} (raw-pixel maxDev=$maxDev, tolerance 8)", maxDev <= 8)
                assertTrue("render/desktop-color: uncovered desktop ROI is NOT near-black" +
                    " (black-paint hypothesis refuted at capture instant;" +
                    " minMaxChannel=$minMaxChannel)", minMaxChannel > 40)
            } finally { bmp.recycle() }
        } catch (t: Throwable) {
            primary = t
            throw t
        } finally {
            // Restore ALWAYS attempted once minimize succeeded; a restore
            // failure is recorded separately and never masks the primary.
            if (guestAborted) {
                Log.e(TAG, "render/restore SKIPPED: unresolved guest-helper cleanup after" +
                    " timeout - xterm may remain minimized; later stages are invalid")
            } else try {
                // Independent finite restore deadline; every call gets the
                // REMAINING time, success can never pass over budget.
                val restoreEnd = System.nanoTime() + 30_000_000_000L
                fun remR(): Long {
                    val r = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        restoreEnd - System.nanoTime())
                    if (r <= 0) throw AssertionError("render/restore: deadline exhausted")
                    return r
                }
                requireGuest(guestExec(rootfs, remR(), "xdotool", "windowactivate", xtId.toString()),
                    "xdotool windowactivate")
                var rst = RenderProbeParse.winStats(requireGuest(
                    guestExec(rootfs, remR(), "xwininfo", "-id", xtId.toString()), "xwininfo restore-poll"))
                while (rst?.mapState != "IsViewable") {
                    Thread.sleep(minOf(500, remR()))
                    rst = RenderProbeParse.winStats(requireGuest(
                        guestExec(rootfs, remR(), "xwininfo", "-id", xtId.toString()), "xwininfo restore-poll"))
                }
                if (rst?.mapState != "IsViewable")
                    throw AssertionError("render/restore: xterm not viewable after windowactivate: " +
                        rst?.mapState)
                if (procStartTime(xtermPid) != xtStart)
                    throw AssertionError("render/restore: xterm process identity changed" +
                        " across minimize/restore")
            } catch (rt: Throwable) {
                if (primary != null) Log.e(TAG, "render/restore ALSO failed (suppressed): " + rt.message)
                else throw AssertionError("render/restore failed: " + rt.message, rt)
            }
        }
        if (System.nanoTime() >= deadlineEnd)
            throw AssertionError("render/probe: completed OVER the overall budget - timing" +
                " evidence invalid")
        Thread.sleep(1_000)
        Shot.save("m1-render-restored")

        // 10. Diagnostic separation: the modules-directory warning is a
        //     DIAGNOSTIC, never a mandatory proof of config usage.
        val glog = File(ctx.filesDir, "mobile-desktop.log").let { if (it.isFile) it.readText() else "" }
        Log.i(TAG, "render-diagnostic (NOT a correctness proof): 'modules directory is not" +
            " accessible' present=" + glog.contains("modules directory is not accessible"))
    }

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

    // ---------------- xdiag proxied-capture diagnostic ----------------
    // Dispatch-gated (instrumentation arg ldoa_xdiag=true) DIAGNOSTIC
    // session capture, never a render gate: the X server listens on :10
    // (XServer token-bound display reservation: debug + disposable-
    // emulator gated, fresh-world only, single-consumption with token
    // attribution) while a test-owned in-guest xtrace proxy FAKES :0 and
    // forwards to :10, so the owner session script, its ready-gate and
    // every guest client keep their hardcoded DISPLAY=:0 untouched and
    // all guest client requests/replies/events are logged from BEFORE
    // the first ready-gate probe. Wire chronology only: NO settle
    // assertions, NO render-PASS claim, NO window pokes/resizes.
    //   * BOUNDED-PHASE contract (not an absolute deadline): startup,
    //     observe, smoke, snapshots and versions each run inside an
    //     explicit monotonic cap; the observation window refuses to
    //     start below a minimum remainder and is durably marked
    //     INCOMPLETE (observation-metadata.txt) when shortened. The
    //     in-guest proxy lifetime covers the phase budget + cleanup
    //     margin only (asserted), so no silent long helper run.
    //   * The OWNED TREE (proot root -> in-guest timeout -> xtrace) is
    //     captured with pid+starttime at anchor time; teardown signals
    //     only the identity-verified anchor, ladders the root, then every
    //     captured tree pid must be confirmed gone (changed/absent
    //     starttime) plus a whole-table argv backstop - any survivor or
    //     unreadable OWN-UID entry is UNKNOWN, never a clean sweep, and
    //     nothing unidentified is ever killed. Foreign-UID entries are
    //     invisible to us by construction and skipped explicitly.
    //   * The trace file is capped AT SOURCE (in-guest ulimit -f, dash
    //     512-byte blocks) and the export has a hard byte cap with
    //     streamed head/marker/tail truncation; the drain keeps only a
    //     capped tail. Export runs even when stop fails.
    // Assets: androidTest/assets/xdiag (MANIFEST.txt = signed-metadata
    // provenance chain, hashes, license, bundled corresponding source,
    // patch spec; regenerate+verify with ci/fetch-xdiag-tools.sh).
    private data class XdiagProxy(
        val root: Process,
        val rootPid: Int,
        val anchorPid: Int,
        val anchorStart: String,
        val tree: Map<Int, String>,
        val diagDir: File,
        val drain: Thread,
        val tail: StringBuilder,
        val drainError: java.util.concurrent.atomic.AtomicReference<Throwable?>,
        val gotCount: java.util.concurrent.atomic.AtomicLong)

    private fun xdiagSha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun xdiagStage(): File {
        // Source assets live in the TEST APK (androidTest source set):
        // they MUST come from the instrumentation context, NOT the target
        // app context (ctx.assets would not carry them). Staging target
        // is the target app's filesDir (reachable by the proot bind).
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val names = testAssets.list("xdiag")
            ?: throw AssertionError("xdiag: assets/xdiag UNREADABLE in the test APK - cannot stage")
        check(names.isNotEmpty()) { "xdiag: assets/xdiag missing from the test APK" }
        // Exclusive create; a collision is refused, never reused.
        val dir = File(ctx.filesDir, "xdiag-" + System.currentTimeMillis() + "-" +
            java.lang.Long.toHexString(System.nanoTime()))
        if (!dir.mkdir()) throw AssertionError("xdiag: staging dir refused/collision: " + dir.absolutePath)
        for (name in names) {
            if (name == "source") continue // GPL corresponding source: redistribution copy, not needed in-guest
            if (name == "proto") {
                val pd = File(dir, "proto"); check(pd.mkdir()) { "xdiag: proto dir create failed" }
                val protos = testAssets.list("xdiag/proto")
                    ?: throw AssertionError("xdiag: assets/xdiag/proto UNREADABLE in the test APK")
                for (pr in protos) {
                    testAssets.open("xdiag/proto/" + pr).use { inp ->
                        File(pd, pr).outputStream().use { inp.copyTo(it) } }
                }
            } else {
                testAssets.open("xdiag/" + name).use { inp ->
                    File(dir, name).outputStream().use { inp.copyTo(it) } }
            }
        }
        check(File(dir, "xtrace").setExecutable(true)) { "xdiag: chmod +x failed" }
        // Runtime verification BEFORE any exec: staged binary hash,
        // protocol decoder count, license presence. Fail closed.
        val hash = xdiagSha256(File(dir, "xtrace"))
        if (hash != XDIAG_XTRACE_SHA256) {
            throw AssertionError("xdiag: staged xtrace sha256 " + hash +
                " != pinned " + XDIAG_XTRACE_SHA256 + " - refusing to exec an unverified binary")
        }
        val protoFiles = File(dir, "proto").listFiles()
            ?: throw AssertionError("xdiag: staged proto dir UNREADABLE")
        val badProto = protoFiles.firstOrNull { !it.isFile || it.length() == 0L }
        if (protoFiles.size != XDIAG_PROTO_COUNT || badProto != null) {
            throw AssertionError("xdiag: proto decoder set incomplete (count=" + protoFiles.size +
                " expected=" + XDIAG_PROTO_COUNT + ", empty=" + (badProto?.name ?: "none") + ")")
        }
        val cop = File(dir, "COPYRIGHT")
        if (!cop.isFile || cop.length() == 0L) throw AssertionError("xdiag: COPYRIGHT missing/empty - redistribution obligation unmet")
        return dir
    }

    /** Three-state pid resolution: GONE only on ENOENT/vanished dir,
     *  ALIVE with starttime, UNKNOWN for anything unreadable-while-
     *  existing. Never collapse UNKNOWN into absent. */
    private enum class XdiagPidState { GONE, ALIVE, UNKNOWN }

    private fun xdiagPidState(pid: Int): Pair<XdiagPidState, String?> {
        val dir = File("/proc", pid.toString())
        val (content, errno) = readProcStat(pid)
        if (content == null) {
            if (errno == android.system.OsConstants.ENOENT || !dir.exists()) return XdiagPidState.GONE to null
            return XdiagPidState.UNKNOWN to null
        }
        val close = content.lastIndexOf(')')
        val rest = if (close >= 0) content.substring(close + 1).trim().split(Regex("\\s+")) else emptyList()
        if (rest.size <= 20) return XdiagPidState.UNKNOWN to null
        return XdiagPidState.ALIVE to rest[19]
    }

    /** Own-UID check for a /proc entry: proot and its tracees run as OUR
     *  uid, so anything foreign-UID cannot be ours and is skipped
     *  explicitly; an entry we cannot stat while it still exists is
     *  UNKNOWN (fail), never skipped. */
    private fun xdiagOwnPidDir(f: File): Boolean {
        val st = try { android.system.Os.stat(f.absolutePath) }
        catch (t: Throwable) {
            if (!f.exists()) return false // vanished mid-scan: not an entry at all
            throw AssertionError("xdiag: cannot stat " + f.absolutePath + " - UNKNOWN", t)
        }
        return st.st_uid.toLong() == android.system.Os.getuid().toLong()
    }

    /** Host /proc scan for OUR-uid processes whose argv[0] is exactly the
     *  given guest path; pid -> starttime. FAIL CLOSED per XdiagPolicy
     *  .scanDecision: foreign-UID skipped, vanished = gone, unreadable
     *  own-UID = UNKNOWN thrown. argv[0] matching excludes wrappers
     *  carrying the path as an ARGUMENT (the in-guest timeout
     *  supervisor). */
    private fun xdiagProcScan(argv0: String): Map<Int, String> {
        val dirs = File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) }
            ?: throw AssertionError("xdiag: /proc listing UNREADABLE - no scan result can be trusted")
        val out = mutableMapOf<Int, String>()
        for (f in dirs) {
            if (!xdiagOwnPidDir(f)) continue // foreign-UID or vanished: cannot be our tracee
            val pid = f.name.toInt()
            val argv = try { File(f, "cmdline").readBytes() } catch (t: Throwable) { null }
            when (XdiagPolicy.scanDecision(isOwnUid = true, readable = argv != null, stillExists = f.exists())) {
                XdiagPolicy.ScanDecision.GONE -> continue
                XdiagPolicy.ScanDecision.UNKNOWN ->
                    throw AssertionError("xdiag: /proc/" + pid + "/cmdline UNREADABLE on OWN pid - UNKNOWN")
                else -> {}
            }
            val first = argv!!.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
            if (first == argv0) {
                val (state, st) = xdiagPidState(pid)
                when (state) {
                    XdiagPidState.GONE -> {}
                    XdiagPidState.UNKNOWN ->
                        throw AssertionError("xdiag: /proc/" + pid + "/stat UNREADABLE on OWN pid - UNKNOWN")
                    XdiagPidState.ALIVE -> out[pid] = st!!
                }
            }
        }
        return out
    }

    /** Full own-UID process tree rooted at [rootPid]: pid -> starttime.
     *  Built from a whole-table ppid map (covers every descendant, not
     *  just direct children); any unreadable own-UID stat is UNKNOWN. */
    private fun xdiagDescendants(rootPid: Int): Map<Int, String> {
        val dirs = File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) }
            ?: throw AssertionError("xdiag: /proc listing UNREADABLE - tree capture impossible")
        val ppidOf = HashMap<Int, Int>()
        val startOf = HashMap<Int, String>()
        for (f in dirs) {
            if (!xdiagOwnPidDir(f)) continue
            val pid = f.name.toInt()
            val (content, errno) = readProcStat(pid)
            if (content == null) {
                if (errno == android.system.OsConstants.ENOENT || !f.exists()) continue
                throw AssertionError("xdiag: /proc/" + pid + "/stat UNREADABLE (errno=" + errno + ") on OWN pid - UNKNOWN")
            }
            val close = content.lastIndexOf(')')
            val rest = if (close >= 0) content.substring(close + 1).trim().split(Regex("\\s+")) else emptyList()
            val pp = (if (rest.size > 20) rest[1].toIntOrNull() else null)
                ?: throw AssertionError("xdiag: /proc/" + pid + "/stat unparsable - UNKNOWN")
            ppidOf[pid] = pp; startOf[pid] = rest[19]
        }
        val out = mutableMapOf<Int, String>()
        val rootStart = startOf[rootPid]
            ?: throw AssertionError("xdiag: spawned proot root " + rootPid + " not in own-UID table - UNKNOWN")
        out[rootPid] = rootStart
        var frontier = listOf(rootPid)
        while (frontier.isNotEmpty()) {
            val next = ppidOf.filter { it.value in frontier && it.key !in out }.keys.toList()
            for (d in next) out[d] = startOf[d]!!
            frontier = next
        }
        return out
    }

    /** Bounded-ladder teardown of a just-spawned helper on start-error
     *  paths: asserts the process is actually DEAD and the drain thread
     *  actually JOINED - a ladder that did not finish is UNKNOWN, not a
     *  quiet best-effort. */
    private fun xdiagReapSpawned(p: Process, drain: Thread?) {
        p.destroy()
        if (!p.waitFor(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly(); p.waitFor(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        drain?.join(2_000)
        if (p.isAlive || drain?.isAlive == true) {
            guestAborted = true
            throw AssertionError("xdiag: spawned helper did not die/join within the bounded ladder - UNKNOWN")
        }
        // A reaped spawn is only clean when NOTHING xtrace-like survives.
        val left = xdiagProcScan("/xdiag/xtrace")
        if (left.isNotEmpty()) {
            guestAborted = true
            throw AssertionError("xdiag: xtrace-like survivor(s) after reaped spawn: " + left.keys +
                " - FAIL UNKNOWN, nothing killed")
        }
    }

    private fun xdiagProxyStart(rootfs: File, diagDir: File, lifetimeSec: Int, deadlineNs: Long): XdiagProxy {
        // Failure-path evidence: EVERY abort below persists the proxy's own
        // output tail, spawn identity, exit status, sanitized argv/env and
        // path resolution into diagDir BEFORE the throw (xdiagExport copies
        // the whole dir). Each artifact write is independent and
        // best-effort, so a cleanup/reap failure can NEVER suppress them.
        // Added after run 41563274576 lost the spawn's stderr tail.
        val abortStage = java.util.concurrent.atomic.AtomicReference("pre-baseline")
        var spawnProc: Process? = null
        var rootPidE: Int? = null
        var argvE: List<String>? = null
        var spawnEnvE: Map<String, String>? = null
        var inheritedE: List<String>? = null
        var sockPreWriteOutcome: List<String>? = null
        var ndE: String? = null
        var preKidsCount = -1
        var postKidsCount = -1
        var drain: Thread? = null
        var rootStartE: String? = null
        val cleanupDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val tail = StringBuilder()
        val drainError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        // Monotonic connection counter - the capped tail can trim and a
        // recount can stay flat or DECREASE, so evidence never comes from
        // tail size; this counter only ever increases.
        val gotCount = java.util.concurrent.atomic.AtomicLong(0)
        try {
        // Collision pre-baseline: any pre-existing xtrace-anchored process
        // is a FOREIGN match we must never touch - abort, kill nothing.
        val baseline = xdiagProcScan("/xdiag/xtrace")
        if (baseline.isNotEmpty()) {
            throw AssertionError("xdiag: pre-existing /xdiag/xtrace process(es) " + baseline.keys +
                " before start - collision refused, nothing killed")
        }
        abortStage.set("spawn-build")
        val nd = ctx.applicationInfo.nativeLibraryDir
        ndE = nd
        val argv = mutableListOf(
            File(nd, "libproot.so").absolutePath,
            "--link2symlink", "-0", "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", XServer.socketDir(ctx).absolutePath + ":/tmp/.X11-unix",
            "-b", diagDir.absolutePath + ":/xdiag",
            "-w", "/root",
            // Trace file capped AT SOURCE: ulimit -f in dash 512-byte
            // blocks (empirically verified) - the writer gets SIGXFSZ at
            // the cap instead of filling the disk.
            "/bin/sh", "-c",
            "ulimit -f " + XdiagPolicy.traceCapBlocks512() + "; exec " +
            "/usr/bin/timeout -s KILL " + lifetimeSec +
            " /xdiag/xtrace -d :10 -D :0 -k -n -o /xdiag/xtrace.log")
        argvE = argv
        val spawnEnv = mapOf(
            "PROOT_LOADER" to File(nd, "libproot_loader.so").absolutePath,
            "PROOT_LOADER_32" to File(nd, "libproot_loader32.so").absolutePath,
            "LD_LIBRARY_PATH" to nd,
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_TMP_DIR" to Proot.prootTmpDir(ctx).absolutePath,
            "TMPDIR" to "/tmp")
        spawnEnvE = spawnEnv
        val pb = ProcessBuilder(argv)
        pb.environment().putAll(spawnEnv)
        inheritedE = (pb.environment().keys - spawnEnv.keys).sorted()
        pb.redirectErrorStream(true)
        // The proot bind source must EXIST before spawn: proot cannot create
        // missing bind sources and drops them with a warning only (run
        // 41567502697: bind dropped, guest /tmp/.X11-unix absent, xtrace bind
        // of ':0' failed ENOENT, spawn exited 1). The product start() only
        // mkdirs()s this dir (XServer.start ~line 1141) and never deletes
        // entries in it (verified: sole delete in start is xserver.log
        // rotation), so pre-creating it here removes or shadows nothing the
        // later server start owns. Diagnostic-only precondition: no deletion,
        // no chmod, existing entries untouched; failure aborts fail-closed.
        abortStage.set("socket-dir")
        val sockDir = XServer.socketDir(ctx)
        XdiagSpawnPrep.ensureDirectoryChecked(sockDir)?.let { err ->
            throw AssertionError("stage=socket-dir: " + err)
        }
        // The collision VERDICT uses the COMPLETE listing (never a capped
        // view); an unreadable listing is an error, not "no entries".
        // Evidence display is bounded separately and says when truncated.
        val sockCheck = XdiagSpawnPrep.inspectEntries(sockDir)
            ?: throw AssertionError("stage=socket-dir: entry listing UNREADABLE -" +
                " refusing to launch an unverifiable proxy")
        // Raw-first evidence BEFORE the verdict throw; the write outcome is
        // kept and persisted with any later abort evidence (never implied).
        sockPreWriteOutcome = XdiagAbortEvidence.write(diagDir, listOf("proxy-socketdir-pre.txt" to {
            "socketDir=" + sockDir.absolutePath +
            "\nread=ok total=" + sockCheck.entries.size +
            " truncated=" + sockCheck.truncated +
            "\ncollisions=" + (if (sockCheck.collisions.isEmpty()) "none"
                else sockCheck.collisions.joinToString(",")) +
            "\n" + sockCheck.shown.joinToString("\n") + "\n" }))
        if (sockCheck.collisions.isNotEmpty()) throw AssertionError(
            "stage=socket-dir: preexisting :0 socket/lock entries (owner unprovable," +
            " left untouched): " + sockCheck.collisions.joinToString(","))
        // Same fail-closed spawn discipline as guestExec.
        abortStage.set("pre-spawn-child-capture")
        val preKids = selfChildren()
            ?: throw AssertionError("xdiag: pre-spawn child capture UNREADABLE - refusing to launch an untrackable proxy")
        preKidsCount = preKids.size
        abortStage.set("spawn")
        val p = pb.start()
        spawnProc = p
        // Stderr drain starts IMMEDIATELY after spawn - before any
        // identity capture - so even pre-identity aborts keep the
        // proxy's own output.
        // Bounded reader: NO unbounded forEachLine - a single line is
        // capped at 4KB (truncated + marked), the tail stays capped, and
        // a read error is RECORDED (surfaced at stop), never swallowed.
        drain = kotlin.concurrent.thread {
            try {
                val r = p.inputStream.bufferedReader()
                val line = StringBuilder()
                val cb = CharArray(4096)
                fun flushLine() {
                    if (line.isEmpty()) return
                    var l = line.toString(); line.setLength(0)
                    if (l.length > 4096) l = l.substring(0, 4096) + " [...LINE TRUNCATED]"
                    if (l.contains("Got connection from")) gotCount.incrementAndGet()
                    synchronized(tail) {
                        tail.append(l).append('\n')
                        if (tail.length > XDIAG_TAIL_CAP) tail.delete(0, tail.length - XDIAG_TAIL_CAP / 2)
                    }
                }
                while (true) {
                    val n = r.read(cb)
                    if (n < 0) break
                    for (i in 0 until n) {
                        if (cb[i] == '\n') flushLine() else {
                            line.append(cb[i])
                            if (line.length > 8192) flushLine()
                        }
                    }
                }
                flushLine()
            } catch (t: Throwable) { drainError.set(t) }
        }
        abortStage.set("post-spawn-identity")
        val postKids = selfChildren()
        if (postKids == null) {
            guestAborted = true
            throw AssertionError("xdiag: post-spawn child capture UNREADABLE - capture aborted (cleanup+evidence in the abort handler)")
        }
        postKidsCount = postKids.size
        val spawnedKids = postKids - preKids.keys
        if (spawnedKids.size != 1) {
            guestAborted = true
            throw AssertionError("xdiag: spawned proot root not uniquely identifiable (diff=" +
                spawnedKids.size + ") - capture aborted (cleanup+evidence in the abort handler)")
        }
        val rootPid = spawnedKids.keys.first()
        rootPidE = rootPid
        rootStartE = spawnedKids[rootPid]
        // Anchor: exactly one guest xtrace process, pid+starttime, proven
        // descendant of our proot root, within a bounded wait. Then the
        // OWNED TREE is captured whole so teardown can prove every member
        // gone. Every failure path reaps first.
        abortStage.set("anchor-wait")
        var proxy: XdiagProxy? = null
        val anchorDeadlineNs = System.nanoTime() +
            minOf(10_000_000_000L, deadlineNs - System.nanoTime())
        while (System.nanoTime() < anchorDeadlineNs) {
            try {
                val scan = xdiagProcScan("/xdiag/xtrace")
                if (scan.size == 1) {
                    val pid = scan.keys.first()
                    if (!xdiagIsDescendantOf(pid, rootPid)) {
                        throw AssertionError("xdiag: sole xtrace pid " + pid +
                            " is NOT a descendant of our proot root " + rootPid +
                            " - refusing to anchor a foreign process")
                    }
                    proxy = XdiagProxy(p, rootPid, pid, scan[pid]!!,
                        xdiagDescendants(rootPid), diagDir, drain, tail, drainError, gotCount)
                    break
                }
                if (scan.size > 1) {
                    throw AssertionError("xdiag: multiple xtrace-like processes " + scan.keys +
                        " - anchor ambiguous (never kill what we cannot identify)")
                }
            } catch (t: AssertionError) {
                guestAborted = true; throw t
            }
            Thread.sleep(250)
        }
        if (proxy == null) {
            guestAborted = true
            throw AssertionError("xdiag: proxy anchor did not appear within 10s - capture aborted (cleanup+evidence in the abort handler)")
        }
        Log.i(TAG, "xdiag: proxy up pid=" + proxy.anchorPid + " starttime=" + proxy.anchorStart +
            " tree=" + proxy.tree.keys + " lifetime=" + lifetimeSec + "s (in-guest timeout-enforced)")
        return proxy
        } catch (t: Throwable) {
            // Cleanup ONCE, idempotent, for any spawned-but-not-returned
            // process - covers AssertionErrors, interruptions and any
            // other Throwable from the scan/tree/log paths. A cleanup
            // failure is SUPPRESSED onto the original, never replaces it.
            guestAborted = true
            val sp = spawnProc
            if (sp != null && cleanupDone.compareAndSet(false, true)) {
                try { xdiagReapSpawned(sp, drain) }
                catch (cleanupFailure: Throwable) { t.addSuppressed(cleanupFailure) }
            }
            // Evidence AFTER the cleanup attempt, whatever happened above.
            // Core artifacts generate+write FIRST, each isolated; the
            // bounded AVC harvest is LAST so its latency or failure can
            // never suppress the spawn/exit/tail evidence.
            try {
                val exitStatus = when {
                    sp == null -> XdiagAbortEvidence.exitStatus(null, null, null, notSpawned = true)
                    else -> try {
                        XdiagAbortEvidence.exitStatus(true, sp.exitValue(), null, false)
                    } catch (e: IllegalThreadStateException) {
                        XdiagAbortEvidence.exitStatus(false, null, null, false)
                    } catch (e: Throwable) {
                        XdiagAbortEvidence.exitStatus(null, null, e.javaClass.simpleName, false)
                    }
                }
                val drainRef = drain
                val artifacts = listOf<Pair<String, () -> String>>(
                    "proxy-abort-stage.txt" to { abortStage.get() + "\n" },
                    "proxy-socketdir-pre-write.txt" to { (sockPreWriteOutcome?.joinToString("\n")
                        ?: "NOT ATTEMPTED (pre-evidence write not reached; see abort stage/error)") + "\n" },
                    "proxy-spawn-argv.txt" to { (argvE?.joinToString("\n") ?: "UNAVAILABLE") + "\n" },
                    "proxy-spawn-env.txt" to { XdiagAbortEvidence.envFacts(spawnEnvE, inheritedE) },
                    "proxy-spawn-paths.txt" to {
                        val cwd = try { File("/proc/self/cwd").canonicalPath }
                            catch (e: Throwable) { "PROBE-ERROR(" + e.javaClass.simpleName + ")" }
                        XdiagAbortEvidence.pathFacts(linkedMapOf(
                            "nativeLibraryDir" to (ndE ?: "UNKNOWN"),
                            "rootfs" to rootfs.absolutePath,
                            "diagDir" to diagDir.absolutePath,
                            "socketDir" to XServer.socketDir(ctx).absolutePath,
                            "prootTmpDir" to Proot.prootTmpDir(ctx).absolutePath,
                            "processCwd" to cwd,
                            "dataLocalTests" to "/data/local/tests")) + "\n" },
                    "proxy-identity.txt" to { listOf(
                        "rootPid=" + (rootPidE?.toString() ?: "UNKNOWN(pre-identity)"),
                        "rootStarttime=" + (rootStartE ?: "UNAVAILABLE"),
                        "preKids=" + preKidsCount,
                        "postKids=" + postKidsCount,
                        "connections=" + gotCount.get()).joinToString("\n") + "\n" },
                    "proxy-stderr-tail.txt" to { XdiagAbortEvidence.tailText(
                        drainRef != null, synchronized(tail) { tail.toString() }) },
                    "proxy-exit-status.txt" to { exitStatus + "\n" +
                        "drainError=" + (drainError.get()?.let {
                            it.javaClass.simpleName + ": " + it.message } ?: "none") + "\n" },
                    "proxy-avc-window.txt" to {
                        // Bounded separately (caller-wait bound only - a
                        // stuck shell worker is abandoned on a daemon
                        // thread, not guaranteed killed); existing AVC
                        // lines with timestamps/pids for attribution
                        // checks - no policy changes.
                        val l = XdiagAbortEvidence.bounded(8_000) {
                            device.executeShellCommand("logcat -d -t 800") }
                        if (l == null) "UNAVAILABLE (logcat read failed/timed out)\n"
                        else XdiagAbortEvidence.filterAvcLines(l).ifEmpty {
                            "(no avc/audit lines in window)\n" } })
                val outcomes = XdiagAbortEvidence.write(diagDir, artifacts)
                Log.i(TAG, "xdiag: abort evidence persisted: " + outcomes.joinToString("; "))
            } catch (persistFailure: Throwable) {
                Log.w(TAG, "xdiag: abort evidence persist FAILED (auxiliary): " +
                    persistFailure.javaClass.simpleName)
            }
            throw t
        }
    }


    /** Fail-closed descendant check: walk the ppid chain of [pid] up to
     *  [rootPid] via /proc stat; unreadable-while-alive link = UNKNOWN. */
    private fun xdiagIsDescendantOf(pid: Int, rootPid: Int): Boolean {
        var cur = pid
        repeat(64) {
            if (cur == rootPid) return true
            if (cur <= 1) return false
            val (content, errno) = readProcStat(cur)
            if (content == null) {
                if (errno == android.system.OsConstants.ENOENT) return false
                throw AssertionError("xdiag: /proc/" + cur + "/stat UNREADABLE (errno=" + errno +
                    ") during ancestry walk - UNKNOWN")
            }
            val close = content.lastIndexOf(')')
            val rest = if (close >= 0) content.substring(close + 1).trim().split(Regex("\\s+")) else emptyList()
            cur = (if (rest.size > 1) rest[1].toIntOrNull() else null)
                ?: throw AssertionError("xdiag: /proc/" + cur + "/stat unparsable - UNKNOWN")
        }
        return false
    }

    /** Stop: union the anchor-time tree with a FRESH descendant capture
     *  (covers descendants spawned after anchor); signal ONLY the
     *  identity-verified anchor; bounded ladder on the proot root; drain
     *  join + drain-error surfacing; then every owned pid must be
     *  confirmed GONE via the three-state pid check (ALIVE with the
     *  captured starttime = survivor, UNREADABLE-while-existing =
     *  UNKNOWN, never collapsed into gone) plus an argv backstop. The
     *  in-guest timeout supervisor is lifetime-bound to xtrace (it exits
     *  when its only child exits) and is covered by the tree union.
     *  A stale :0 socket is removed only while OUR token still owns the
     *  live attempt (attempt-sequence attribution); otherwise UNKNOWN.
     *  Nothing unidentified is ever killed. */
    private fun xdiagProxyStop(px: XdiagProxy, token: Long) {
        // Fresh descendant capture: an ABSENT root (pid confirmed GONE)
        // simply has no fresh tree. An UNREADABLE own-UID /proc table is a
        // different fact and must propagate as UNKNOWN - never swallowed
        // into a false "clean". Root reuse by a foreign UID makes
        // xdiagDescendants fail closed ("not in own-UID table").
        // Root identity is checked BEFORE any traversal: ALIVE with a
        // mismatched starttime is a REUSED root - UNKNOWN immediately,
        // never capture a foreign tree first. GONE (confirmed absent) is
        // the only no-tree case. After traversal the root is re-verified:
        // if it changed mid-capture the captured tree is discarded as
        // UNKNOWN, never treated as clean.
        val capturedRootStart = px.tree[px.rootPid]
        val (rootState, rootNow) = xdiagPidState(px.rootPid)
        val treeNow = when {
            rootState == XdiagPidState.GONE -> emptyMap()
            rootState == XdiagPidState.UNKNOWN -> {
                guestAborted = true
                throw AssertionError("xdiag: proot root " + px.rootPid + " unreadable at stop - UNKNOWN")
            }
            rootNow != capturedRootStart -> {
                guestAborted = true
                throw AssertionError("xdiag: proot root " + px.rootPid + " REUSED before tree capture" +
                    " (start " + capturedRootStart + " -> " + rootNow + ") - UNKNOWN, no tree attached")
            }
            else -> {
                val t = xdiagDescendants(px.rootPid)
                val (s2, n2) = xdiagPidState(px.rootPid)
                if (s2 != XdiagPidState.ALIVE || n2 != capturedRootStart) {
                    guestAborted = true
                    throw AssertionError("xdiag: proot root changed DURING tree capture (" +
                        s2 + ", start " + capturedRootStart + " -> " + n2 + ") - UNKNOWN, tree discarded")
                }
                t
            }
        }
        // Identity conflict = PID REUSE between the anchor-time tree and
        // now. Preserve BOTH observations; a reused ROOT means the fresh
        // tree belongs to a foreign process and must NOT be attached.
        val foreign = mutableMapOf<Int, Pair<String, String>>()
        for (pid in px.tree.keys.intersect(treeNow.keys)) {
            if (px.tree[pid] != treeNow[pid]) foreign[pid] = Pair(px.tree[pid]!!, treeNow[pid]!!)
        }
        if (px.rootPid in foreign) {
            guestAborted = true
            throw AssertionError("xdiag: proot root pid " + px.rootPid + " REUSED (start " +
                foreign[px.rootPid]!!.first + " -> " + foreign[px.rootPid]!!.second +
                ") - refusing to attach a foreign tree - UNKNOWN")
        }
        if (foreign.isNotEmpty()) {
            Log.w(TAG, "xdiag: pid reuse between anchor and stop (old/new start preserved): " + foreign)
        }
        // Reused non-root pids: the old owner is gone and the current
        // process is not ours - exclude BOTH from the owned sweep.
        val ownedTree = (px.tree - foreign.keys) + (treeNow - foreign.keys)
        // Identity equality BEFORE any signal: ALIVE with a DIFFERENT
        // starttime means the pid was reused by a process we do not own -
        // never signal it.
        val (anchorState, anchorNow) = xdiagPidState(px.anchorPid)
        when {
            anchorState == XdiagPidState.UNKNOWN -> {
                guestAborted = true
                throw AssertionError("xdiag: anchor pid " + px.anchorPid + " unreadable at stop - UNKNOWN")
            }
            anchorState == XdiagPidState.ALIVE && anchorNow == px.anchorStart ->
                android.os.Process.killProcess(px.anchorPid)
            anchorState == XdiagPidState.ALIVE ->
                Log.i(TAG, "xdiag: anchor pid " + px.anchorPid + " reused (start " +
                    px.anchorStart + " -> " + anchorNow + ") - NOT ours, no signal sent")
            else -> {} // GONE: anchor already dead
        }
        px.root.destroy()
        if (!px.root.waitFor(3_000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            px.root.destroyForcibly(); px.root.waitFor(3_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        px.drain.join(2_000)
        if (px.root.isAlive || px.drain.isAlive) {
            guestAborted = true
            throw AssertionError("xdiag: proxy root/drain did not finish within the bounded ladder - UNKNOWN")
        }
        px.drainError.get()?.let {
            // No proof the read error is a benign shutdown race: a broken
            // drain means capture output may be incomplete - UNKNOWN.
            guestAborted = true
            throw AssertionError("xdiag: proxy drain read failed (" + it.javaClass.simpleName +
                ": " + (it.message ?: "").take(120) + ") - capture possibly incomplete - UNKNOWN")
        }
        val survivors = mutableListOf<Int>()
        for ((pid, st) in ownedTree) {
            val (state, now) = xdiagPidState(pid)
            when (state) {
                XdiagPidState.GONE -> {}
                XdiagPidState.ALIVE -> if (now == st) survivors.add(pid)
                XdiagPidState.UNKNOWN -> {
                    guestAborted = true
                    throw AssertionError("xdiag: owned pid " + pid + " unreadable at survivor sweep - UNKNOWN")
                }
            }
        }
        if (survivors.isNotEmpty()) {
            guestAborted = true
            throw AssertionError("xdiag: owned-tree survivor(s) after anchored stop: " + survivors +
                " - FAIL UNKNOWN, nothing further killed")
        }
        val escaped = xdiagProcScan("/xdiag/xtrace")
        if (escaped.isNotEmpty()) {
            guestAborted = true
            throw AssertionError("xdiag: xtrace-like process(es) outside the owned tree: " + escaped.keys +
                " - FAIL UNKNOWN, nothing killed")
        }
        val sock = File(XServer.socketDir(ctx), "X0")
        if (sock.exists()) {
            // ATOMIC guard+delete under the seam lock: ownership is
            // re-verified and the file removed inside one lock hold, so no
            // start can interleave between check and delete. A stale
            // socket we do not verifiably own stays UNKNOWN and is never
            // deleted unidentified.
            if (!XServer.deleteOwnedTestSocketForTest(token, sock)) {
                guestAborted = true
                throw AssertionError("xdiag: stale :0 socket present but the live attempt is not ours" +
                    " (or delete refused/failed) - UNKNOWN, socket left in place")
            }
        }
        File(px.diagDir, "xtrace.log").let {
            Log.i(TAG, "xdiag: proxy stopped clean; trace " + (if (it.isFile) it.length().toString() + " bytes" else "ABSENT"))
        }
    }

    /** Abort-guarded, code-checked guest command bounded by the ONE
     *  overall deadline: no shell pipelines, the timeout latch is honored
     *  BEFORE spawning, unclean exec latches the abort, non-zero rc is a
     *  hard failure, and the effective timeout is min(command cap,
     *  remaining overall budget) - a command that cannot fit fails as
     *  budget-exhausted UNKNOWN instead of running under-budgeted. */
    private fun xdiagGuest(rootfs: File, deadlineNs: Long, what: String, vararg args: String): String {
        if (guestAborted) throw AssertionError("xdiag: " + what +
            " refused - an earlier guest timeout/unclean exec latched the abort")
        val remMs = (deadlineNs - System.nanoTime()) / 1_000_000L
        if (remMs <= 0L) throw AssertionError("xdiag: overall budget EXHAUSTED before " + what + " - UNKNOWN")
        val out = guestExec(rootfs, minOf(XdiagPolicy.COMMAND_BUDGET_MS, remMs), *args)
        if (out.leftover.isNotEmpty() || guestAborted) {
            guestAborted = true
            throw AssertionError("xdiag: " + what + " exec UNCLEAN (leftover=" + out.leftover +
                ") - UNKNOWN, aborting capture")
        }
        if (out.code != 0) {
            throw AssertionError("xdiag: " + what + " failed rc=" + out.code +
                " out=" + out.text.take(400))
        }
        return out.text
    }

    /** FIRST-SNAPSHOT pcmanfm identity: exactly one live pcmanfm pid,
     *  anchored with its HOST starttime (guest /proc is the bound host
     *  /proc). LIMITATION, kept explicit: this anchors the process found
     *  at ready/first-snapshot time - it is NOT proof of the original
     *  launch identity (a pre-ready restart would be invisible). Every
     *  later snapshot re-verifies pid+starttime, so a restart AFTER the
     *  anchor is UNKNOWN, never silently re-anchored. */
    private fun xdiagPcmanAnchor(rootfs: File, deadlineNs: Long): Pair<Int, String> {
        val pids = xdiagGuest(rootfs, deadlineNs, "pcmanfm pid",
            "pgrep", "-x", "pcmanfm").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (pids.size != 1) throw AssertionError("xdiag: pcmanfm pid not unique (count=" + pids.size + ") - UNKNOWN")
        val pid = pids[0].toIntOrNull() ?: throw AssertionError("xdiag: pcmanfm pid unparsable: " + pids[0])
        val st = procStartTime(pid) ?: throw AssertionError("xdiag: pcmanfm pid " + pid + " starttime unreadable - UNKNOWN")
        return pid to st
    }

    /** Identity-chained snapshot: desktop = exactly one window of the
     *  anchored first-snapshot pcmanfm that parses (strict anchored
     *  parsers via XdiagPolicy, no contains-matching) as WM_CLASS
     *  pcmanfm + type DESKTOP + IsViewable + _NET_WM_PID == anchor.
     *  Raw evidence is PERSISTED INCREMENTALLY as collected (flushed per
     *  step) - a failure mid-snapshot leaves the partial raw file, never
     *  an in-memory-only loss. */
    private fun xdiagSnapshot(rootfs: File, deadlineNs: Long, diagDir: File, tag: String,
                              pcman: Pair<Int, String>) {
        val out = File(diagDir, "snapshot-" + tag + ".txt")
        out.writer().use { w ->
            fun emit(line: String) { w.write(line); w.flush() }
            val cur = xdiagPcmanAnchor(rootfs, deadlineNs)
            if (cur != pcman) {
                emit("FAIL: pcmanfm identity changed " + pcman + " -> " + cur + "\n")
                throw AssertionError("xdiag: pcmanfm identity changed " + pcman +
                    " -> " + cur + " (post-anchor restart) - UNKNOWN")
            }
            emit("pcmanfm-anchor (first-snapshot identity, NOT launch-proof): pid=" +
                pcman.first + " starttime=" + pcman.second + "\n")
            val candRaw = xdiagGuest(rootfs, deadlineNs, "window search",
                "xdotool", "search", "--class", "pcmanfm")
            emit("-- raw candidate list --\n" + candRaw)
            val candidates = candRaw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            var desktop: String? = null
            val typeRx = Regex("(?m)^_NET_WM_WINDOW_TYPE\\(ATOM\\) = _NET_WM_WINDOW_TYPE_DESKTOP$")
            val pidRx = Regex("(?m)^_NET_WM_PID\\(CARDINAL\\) = (\\d+)$")
            val candidateFailures = mutableListOf<String>()
            val desktops = mutableListOf<String>()
            for (c in candidates) {
                // Persist each candidate's raw evidence IMMEDIATELY as
                // fetched; a failure on a later candidate or command can
                // never take an earlier candidate's raw data with it.
                val typePidClass = try {
                    xdiagGuest(rootfs, deadlineNs, "window type/pid/class",
                        "xprop", "-id", c, "_NET_WM_WINDOW_TYPE", "_NET_WM_PID", "WM_CLASS")
                } catch (t: Throwable) {
                    candidateFailures.add(c + " xprop: " + (t.message ?: "failed").take(200))
                    emit("-- candidate " + c + " xprop FAILED --\n" + (t.message ?: "").take(300) + "\n")
                    continue
                }
                emit("-- candidate " + c + " xprop --\n" + typePidClass)
                val info = try {
                    xdiagGuest(rootfs, deadlineNs, "window map state", "xwininfo", "-id", c)
                } catch (t: Throwable) {
                    candidateFailures.add(c + " xwininfo: " + (t.message ?: "failed").take(200))
                    emit("-- candidate " + c + " xwininfo FAILED --\n" + (t.message ?: "").take(300) + "\n")
                    continue
                }
                emit("-- candidate " + c + " xwininfo --\n" + info)
                val matches = typeRx.containsMatchIn(typePidClass) &&
                    pidRx.find(typePidClass)?.groupValues?.get(1) == pcman.first.toString() &&
                    XdiagPolicy.wmClassIsPcmanfm(typePidClass) &&
                    info.lineSequence().any { it.trim() == "Map State: IsViewable" }
                if (matches) desktops.add(c)
            }
            // Qualify only AFTER every candidate's raw evidence (or its
            // failure outcome) is persisted.
            if (candidateFailures.isNotEmpty()) {
                emit("FAIL: candidate collection incomplete: " + candidateFailures + "\n")
                throw AssertionError("xdiag: window candidate collection incomplete " +
                    candidateFailures + " - UNKNOWN (raw evidence preserved)")
            }
            if (desktops.size > 1) {
                emit("FAIL: multiple DESKTOP windows of anchored pcmanfm: " + desktops + "\n")
                throw AssertionError("xdiag: multiple DESKTOP windows of anchored pcmanfm " +
                    pcman.first + " - UNKNOWN")
            }
            val d = desktops.firstOrNull() ?: run {
                emit("FAIL: no unique DESKTOP window of anchored pcmanfm\n")
                throw AssertionError("xdiag: no unique DESKTOP window of anchored pcmanfm " +
                    pcman.first + " - UNKNOWN")
            }
            emit("desktop-window: " + d + "\n")
            emit("-- WM_NORMAL_HINTS --\n" +
                xdiagGuest(rootfs, deadlineNs, "desktop hints", "xprop", "-id", d, "WM_NORMAL_HINTS"))
            val geo = xdiagGuest(rootfs, deadlineNs, "desktop geometry", "xwininfo", "-id", d)
            emit("-- geometry --\n" + geo.lineSequence()
                .filter { it.contains("Absolute") || it.contains("Width:") || it.contains("Height:") }
                .joinToString("\n") + "\n")
            emit("-- monitors --\n" +
                xdiagGuest(rootfs, deadlineNs, "monitor list", "xrandr", "--listmonitors"))
        }
        Log.i(TAG, "xdiag: snapshot-" + tag + " captured (anchored, raw persisted incrementally)")
    }

    private fun xdiagVersions(rootfs: File, deadlineNs: Long, diagDir: File) {
        val text = xdiagGuest(rootfs, deadlineNs, "guest dpkg versions",
            "dpkg-query", "-W", "jwm", "pcmanfm", "libgtk-3-0", "libfm-gtk4", "libfm4", "librandr2", "libx11-6")
        File(diagDir, "guest-dpkg-versions.txt").writeText(text)
        Log.i(TAG, "xdiag: guest dpkg versions captured (rc checked)")
    }

    private fun xdiagProxyAlive(px: XdiagProxy): Boolean =
        procStartTime(px.anchorPid) == px.anchorStart

    /** Bounded trace read: text of the trace past byte offset [base],
     *  capped at TRACE_READ_CAP bytes per call (RandomAccessFile seek,
     *  never a whole-file read). A window larger than the cap is a hard
     *  failure - the caller's assertion domain must stay bounded. */
    private fun xdiagTraceWindow(trace: File, base: Long): String {
        if (!trace.isFile) return ""
        java.io.RandomAccessFile(trace, "r").use { raf ->
            val len = raf.length()
            if (len <= base) return ""
            val n = len - base
            if (n > XdiagPolicy.TRACE_READ_CAP) throw AssertionError(
                "xdiag: trace window " + n + " bytes exceeds read cap - UNKNOWN (refusing unbounded read)")
            val buf = ByteArray(n.toInt())
            raf.seek(base); raf.readFully(buf)
            return String(buf, Charsets.UTF_8)
        }
    }

    /** Identified, non-mutating, CONNECTION-CORRELATED proxy smoke: our
     *  own `xrandr -display :0 --listmonitors` opens a NEW client
     *  connection through the proxy. The offset-anchored trace window
     *  must show ONE connection id carrying the setup handshake, the
     *  Success reply AND our GetMonitors request decode (same id), and
     *  the proxy's stderr tail must gain a "Got connection from" line.
     *  Connection discrimination (verified scope): the ready-gate's
     *  `xset q` polls are NEW short-lived connections per poll
     *  (reviewer-confirmed from xset source), so "gate polls are
     *  long-lived earlier connections" is NOT assumed anywhere. xset q
     *  issues no RANDR GetMonitors, so the same-id
     *  setup+Success+GetMonitors correlation excludes those polls.
     *  Remaining assumption, stated not hidden: no OTHER client issues
     *  GetMonitors inside the smoke window; if one did, the correlation
     *  still proves the fresh-client capture path but not WHICH client.
     *  Line shapes grounded in the actual local xtrace logs (the
     *  --timestamps flag was REMOVED after source review showed it
     *  prepends epoch.ms before the connection id), not invented. */
    private fun xdiagSmoke(rootfs: File, deadlineNs: Long, px: XdiagProxy) {
        val trace = File(px.diagDir, "xtrace.log")
        val base = if (trace.isFile) trace.length() else 0L
        val tailBefore = px.gotCount.get() // monotonic drain counter, not a tail recount
        val monitors = xdiagGuest(rootfs, deadlineNs, "smoke probe",
            "xrandr", "-display", ":0", "--listmonitors")
        if (!monitors.contains("Monitors:")) {
            throw AssertionError("xdiag: smoke probe output unparsable - UNKNOWN\n" + monitors.take(400))
        }
        while (System.nanoTime() < deadlineNs) {
            val window = xdiagTraceWindow(trace, base)
            val correlated = XdiagPolicy.correlatedGetMonitorsConnections(window)
            val tailAfter = px.gotCount.get()
            if (correlated.isNotEmpty() && tailAfter > tailBefore) {
                // Honest attribution: a FRESH GetMonitors-carrying
                // connection appeared in our probe window with the probe
                // round-trip verified. If the ready-gate opens fresh
                // GetMonitors connections of its own, this proves the
                // capture path for fresh client traffic, not which
                // client - that is the intended proof strength.
                Log.i(TAG, "xdiag: smoke PASSED - fresh connection " + correlated +
                    " carries setup+reply+GetMonitors decode in the probe window" +
                    " (fresh-client capture proof); tail connections " + tailBefore + "->" + tailAfter)
                return
            }
            if (!xdiagProxyAlive(px)) throw AssertionError("xdiag: proxy died during smoke - UNKNOWN")
            Thread.sleep(500)
        }
        throw AssertionError("xdiag: smoke FAILED - probe not correlated in the trace within budget" +
            " (no single connection with setup+reply+GetMonitors); capture path unproven -" +
            " existing artifacts are still exported best-effort and the diagnostic marked unusable")
    }

    /** Streaming trace census (bounded line scan, whole file is capped at
     *  source by ulimit): records what the session ACTUALLY sent. Absence
     *  is recorded as diagnostic data, NEVER treated as coverage. */
    private fun xdiagTraceAnalysis(diagDir: File) {
        val trace = File(diagDir, "xtrace.log")
        var getMonitors = 0; var configure = 0; var conns = mutableSetOf<String>()
        if (trace.isFile) {
            trace.bufferedReader().use { r ->
                r.forEachLine { line ->
                    if (line.contains("GetMonitors")) getMonitors++
                    if (line.contains("ConfigureWindow")) configure++
                    val m = Regex("^([0-9]+):").find(line) // %03d is MINIMUM width
                    if (m != null) conns.add(m.groupValues[1])
                }
            }
        }
        File(diagDir, "trace-analysis.txt").writeText(
            "trace bytes: " + (if (trace.isFile) trace.length() else -1) + "\n" +
            "GetMonitors requests+replies: " + getMonitors + "\n" +
            "ConfigureWindow requests: " + configure + "\n" +
            "distinct connection ids: " + conns.size + "\n" +
            "NOTE: absence of an expected request is a FINDING about the session,\n" +
            "not proof the capture covered it; only the smoke probe is a coverage proof.\n")
    }

    private fun xdiagCountGot(haystack: String): Int =
        Regex("Got connection from").findAll(haystack).count()

    /** Export with a HARD total byte cap, streamed (no whole-file
     *  reads), planned by XdiagPolicy.exportPlan. FAILURE IS LOUD: a
     *  missing dir, a copy error, a size mismatch, or an omitted file
     *  throws - the caller treats any export failure as diagnostic
     *  UNUSABLE (never "capture complete"). */
    private fun xdiagExport(diagDir: File) {
        val out = ctx.getExternalMediaDirs().firstOrNull()?.let { File(it, "additional_test_output") }
            ?: throw AssertionError("xdiag: export FAILED - no external media dir; diagnostic unusable")
        val dest = File(out, "xdiag")
        if (!dest.isDirectory && !dest.mkdirs())
            throw AssertionError("xdiag: export FAILED - cannot create " + dest.absolutePath + "; diagnostic unusable")
        // Export existing evidence FIRST (an early failure must not
        // suppress the partial trace that explains it); completeness is
        // judged AFTER, against the required inventory.
        val present = (diagDir.listFiles() ?: emptyArray()).filter { it.isFile }.map { it.name }.toSet()
        val files = (diagDir.listFiles() ?: emptyArray()).filter { it.isFile }
            .sortedBy { if (it.name == "xtrace.log") 1 else 0 }
        var used = 0L
        val problems = mutableListOf<String>()
        for (f in files) {
            val remaining = XdiagPolicy.EXPORT_CAP_BYTES - used
            val marker = ("\n[... TRUNCATED by xdiag export cap ...]\n").toByteArray(Charsets.UTF_8)
            try {
                val target = File(dest, f.name)
                val size = f.length()
                when (val plan = XdiagPolicy.exportPlan(size, remaining, marker.size)) {
                    is XdiagPolicy.ExportPlan.Full -> {
                        // Bounded exact-size copy: never reads past the
                        // size captured at plan time, so a still-growing
                        // source can never push the write beyond budget;
                        // growth is detected AFTER via re-stat.
                        var n = 0L
                        f.inputStream().use { i -> target.outputStream().use { o ->
                            val buf = ByteArray(65536)
                            while (n < size) {
                                val r = i.read(buf, 0, minOf(buf.size.toLong(), size - n).toInt())
                                if (r < 0) break
                                o.write(buf, 0, r); n += r
                            } } }
                        if (n != size) throw AssertionError("short copy " + n + " != " + size)
                        if (target.length() != size)
                            throw AssertionError("exported size mismatch for " + f.name)
                        if (f.length() != size)
                            throw AssertionError("source " + f.name + " GREW during copy (" +
                                size + " -> " + f.length() + ") - capture still live")
                        used += size
                    }
                    is XdiagPolicy.ExportPlan.Truncated -> {
                        if (plan.head == 0 && plan.tail == 0) {
                            target.writeBytes(marker)
                            used += marker.size
                        } else {
                            java.io.RandomAccessFile(f, "r").use { raf ->
                                target.outputStream().use { o ->
                                    val buf = ByteArray(plan.head); raf.readFully(buf); o.write(buf)
                                    o.write(marker)
                                    raf.seek(size - plan.tail)
                                    val tb = ByteArray(plan.tail); raf.readFully(tb); o.write(tb)
                                }
                            }
                            used += plan.head + plan.markerLen + plan.tail
                        }
                        Log.w(TAG, "xdiag: " + f.name + " truncated to export cap")
                    }
                    XdiagPolicy.ExportPlan.Omit ->
                        throw AssertionError("no budget even for a marker (" + remaining + " bytes left)")
                }
            } catch (t: Throwable) {
                // A partial failed file still OCCUPIED cap budget: count
                // its written bytes before removing it so later files
                // cannot overrun the disk total.
                val target = File(dest, f.name)
                if (target.isFile) { used += target.length(); target.delete() }
                problems.add(f.name + ": " + (t.message ?: t.javaClass.simpleName))
            }
        }
        val required = setOf("xtrace.log", "snapshot-ready.txt", "snapshot-settled.txt",
            "guest-dpkg-versions.txt", "trace-analysis.txt", "observation-metadata.txt")
        val missing = required - present
        if (missing.isNotEmpty()) problems.add("required artifact(s) missing: " + missing)
        if (problems.isNotEmpty()) {
            throw AssertionError("xdiag: export INCOMPLETE (" + problems.joinToString("; ") +
                ") - diagnostic UNUSABLE; existing evidence preserved at " + dest.absolutePath)
        }
        Log.i(TAG, "xdiag: artifacts exported and verified at additional_test_output/xdiag (" +
            used + " bytes, cap " + XdiagPolicy.EXPORT_CAP_BYTES + ")")
    }

    @Test
    fun mobileEntryXdiagCapture() {
        // setUp already early-skips when ldoa_xdiag != "true"; this
        // in-method assume is belt-and-braces for direct method runs.
        org.junit.Assume.assumeTrue(
            "xdiag capture is dispatch-gated (ldoa_xdiag=true) - skipped in normal runs",
            InstrumentationRegistry.getArguments().getString("ldoa_xdiag") == "true")
        // Bounded-phase contract: a monotonic overall budget, with each
        // phase capped by an explicit cap or the remaining budget; the
        // observation phase can never silently become zero (UNKNOWN) or
        // unlabelled-short (durable INCOMPLETE metadata). The in-guest
        // proxy lifetime covers the budget plus the fixed cleanup margin
        // - asserted, so no silent longer helper run.
        val totalMs = XdiagPolicy.totalBudgetMs(START_BUDGET_MS)
        val lifetimeSec = XdiagPolicy.proxyLifetimeSec(totalMs)
        check(lifetimeSec * 1000L <= totalMs + XdiagPolicy.CLEANUP_MARGIN_MS + 1000L) {
            "xdiag: proxy lifetime exceeds budget+cleanup margin - incoherent budgets" }
        val deadlineNs = System.nanoTime() + totalMs * 1_000_000L
        // Collision pre-flight: no leftover X sockets from any earlier
        // attempt (socket/lock names grounded in E1PresentationBridgeTest's
        // socketDir scan); then arm the token-bound display reservation.
        val sockDir = XServer.socketDir(ctx)
        for (n in listOf("X0", "X0-lock", "X10", "X10-lock")) {
            check(!File(sockDir, n).exists()) { "xdiag: stale socket " + n + " present before start - refusing" }
        }
        check(XServer.displaySeamReservationForTest() == null) {
            "xdiag: display seam already reserved - refusing a concurrent diagnostic attempt" }
        var token = 0L
        val prefs = ctx.getSharedPreferences("plinux_desktop", Context.MODE_PRIVATE)
        val rootfs = DistroInstaller(ctx).rootfsDir(Distros.UBUNTU)
        var diagDir: File? = null
        var proxy: XdiagProxy? = null
        var primary: Throwable? = null
        try {
            if (deadlineNs - System.nanoTime() <= 0L) throw AssertionError(
                "xdiag: overall budget EXHAUSTED before staging - UNKNOWN")
            val staged = xdiagStage(); diagDir = staged
            proxy = xdiagProxyStart(rootfs, staged, lifetimeSec, deadlineNs)
            val px = proxy!!
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                device.removeWatcher("phonelinux-anr")
                // EXCLUSIVE diagnostic launch: the reservation is armed and
                // OUR identified start initiated in one atomic lock hold;
                // any other caller is refused while armed, and the consumer
                // must present OUR token. No UI picker/consent path - those
                // are normal callers and would (correctly) be refused.
                scenario.onActivity { a ->
                    token = XServer.startDiagnosticSessionForTest(":10", a, Distros.UBUNTU,
                        mode = XServer.DesktopMode.MOBILE)
                }
                var live = false
                while (System.nanoTime() < deadlineNs) {
                    if (!xdiagProxyAlive(px)) throw AssertionError(
                        "xdiag: proxy died during session startup - UNKNOWN (dead capture path)")
                    val proc = XServer.sessionProcess
                    if (proc != null && proc.isAlive && prefs.getBoolean("mobile_desktop_active", false)) { live = true; break }
                    Thread.sleep(500)
                }
                if (!live) {
                    captureFailureEvidence("xdiag-liveness")
                    org.junit.Assert.fail("xdiag: session did not reach liveness within budget - capture aborted (diagnostic, no render claim)")
                }
                // Seam attribution: OUR token must be the one start()
                // consumed - otherwise the server is NOT provably on :10.
                // Live ownership, not token equality alone: the seam must
                // be attributed to OUR still-current start attempt with a
                // live server - an unintended earlier consumer or an
                // intervening start makes this false.
                if (!XServer.displaySeamOwnedByForTest(token)) {
                    org.junit.Assert.fail("xdiag: display seam not owned by our live attempt" +
                        " (token attribution failed) - server display unproven, capture aborted")
                }
                // Identified, connection-correlated, non-mutating probe
                // through the proxy BEFORE any artifact is trusted.
                xdiagSmoke(rootfs, deadlineNs, px)
                val pcman = xdiagPcmanAnchor(rootfs, deadlineNs)
                xdiagSnapshot(rootfs, deadlineNs, staged, "ready", pcman)
                // Bounded observation window over the resize cascade (CI
                // evidence: clients then first resize ~8s, cascade < 2s) -
                // MONOTONIC end time, chunked to catch proxy death.
                // Clamped to the overall deadline minus the post-observe
                // reserve (settled snapshot + versions + analysis, each
                // command-budgeted) - observe can never starve teardown.
                // Bounded-phase contract (NOT an absolute deadline
                // claim): each phase has an explicit cap; if the reserve
                // was already consumed, the observation must NOT silently
                // become zero - that is UNKNOWN. A shortened window is
                // labeled INCOMPLETE in the log, never passed as full.
                val postReserveNs = (4 * XdiagPolicy.COMMAND_BUDGET_MS + 30_000L) * 1_000_000L
                val observeEndNs = minOf(System.nanoTime() + XdiagPolicy.OBSERVE_MS * 1_000_000L,
                    deadlineNs - postReserveNs)
                val observeLeftMs = (observeEndNs - System.nanoTime()) / 1_000_000L
                if (observeLeftMs < 5_000L) throw AssertionError(
                    "xdiag: no observation budget left (" + observeLeftMs + "ms < 5000ms)" +
                    " after the post-reserve - UNKNOWN, refusing a zero-length observation")
                val observeStartNs = System.nanoTime()
                while (System.nanoTime() < observeEndNs) {
                    if (!xdiagProxyAlive(px)) throw AssertionError(
                        "xdiag: proxy died during the observe window - UNKNOWN (dead capture path)")
                    Thread.sleep(1_000)
                }
                // Durable observation metadata (not log-only): intended
                // window, the budget actually available at loop start,
                // the actual elapsed, and a completeness verdict with a
                // clear 2s scheduling tolerance. A shortened-but-honest
                // window is COMPLETE against its budget; anything short
                // of the budget beyond the tolerance is INCOMPLETE.
                val actualMs = (System.nanoTime() - observeStartNs) / 1_000_000L
                // Two DISTINCT facts: did we complete the budgeted
                // interval, and did we achieve the full intended window.
                val budgetComplete = actualMs + 2_000L >= observeLeftMs
                val intendedComplete = actualMs + 2_000L >= XdiagPolicy.OBSERVE_MS
                val shortenedReason = if (observeLeftMs < XdiagPolicy.OBSERVE_MS)
                    "overall-budget reserve" else "none"
                File(staged, "observation-metadata.txt").writeText(
                    "intended_window_ms: " + XdiagPolicy.OBSERVE_MS + "\n" +
                    "budgeted_window_ms: " + observeLeftMs + "\n" +
                    "actual_elapsed_ms: " + actualMs + "\n" +
                    "tolerance_ms: 2000\n" +
                    "budget_complete: " + budgetComplete + "\n" +
                    "intended_complete: " + intendedComplete + "\n" +
                    "shortened_reason: " + shortenedReason + "\n")
                if (!budgetComplete) Log.w(TAG, "xdiag: observation fell short of its BUDGETED window" +
                    " - actual " + actualMs + "ms vs budgeted " + observeLeftMs + "ms")
                if (!intendedComplete) Log.w(TAG, "xdiag: observation shortened vs intended " +
                    XdiagPolicy.OBSERVE_MS + "ms (reason: " + shortenedReason + ")")
                xdiagSnapshot(rootfs, deadlineNs, staged, "settled", pcman)
                xdiagVersions(rootfs, deadlineNs, staged)
                xdiagTraceAnalysis(staged)
                // Final identity recheck AFTER all captures: the anchored
                // pcmanfm and the proxy must still be the same processes.
                if (xdiagPcmanAnchor(rootfs, deadlineNs) != pcman) throw AssertionError(
                    "xdiag: pcmanfm identity changed before final recheck - UNKNOWN")
                if (!xdiagProxyAlive(px)) throw AssertionError(
                    "xdiag: proxy identity changed before final recheck - UNKNOWN")
            }
        } catch (t: Throwable) { primary = t }
        // Cleanup, ALWAYS in order: stop proxy (proves tree gone, removes
        // its stale socket under our token) -> export (never skipped by a
        // stop failure) -> release the reservation.
        var cleanup: Throwable? = null
        try { proxy?.let { xdiagProxyStop(it, token) } } catch (t: Throwable) { cleanup = t }
        // Export FAILURE = diagnostic unusable: xdiagExport throws and
        // the failure lands in cleanup even when stop already failed.
        try { diagDir?.let { xdiagExport(it) } } catch (t: Throwable) { if (cleanup == null) cleanup = t }
        try { XServer.releaseServerDisplayForTest(token) } catch (t: Throwable) { if (cleanup == null) cleanup = t }
        val p = primary; val c = cleanup
        when {
            p != null && c != null -> { p.addSuppressed(c); throw p }
            p != null -> throw p
            c != null -> throw AssertionError("xdiag: cleanup failed", c)
        }
        Log.i(TAG, "xdiag: capture complete with VERIFIED export - DIAGNOSTIC ONLY:" +
            " no settle assertions, no render-PASS claim")
    }


    @Test
    fun mobileEntryLifecycle() {
        val prefs = ctx.getSharedPreferences("plinux_desktop", Context.MODE_PRIVATE)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Obstruction policy for the WHOLE test: exactly ONE verified
            // launcher-ANR Wait dismissal total (shared counter), armed from
            // the EARLIEST entry/navigation obstruction - the readiness gate
            // before the first tap - through the pre-focus gate below. No
            // broad dismissal (dismissSystemDialogs is NOT called - it would
            // register the auto-clicking watcher and add silent clicks), no
            // watchers, no close-app, no launcher disable/replace, no extra
            // clicks beyond that one Wait. The watcher is removed explicitly
            // in case any harness path registered it.
            device.removeWatcher("phonelinux-anr")
            Log.i(TAG, "m1-arm: policy INSTALLED (init) - ANR watcher removed; dialog" +
                " handling = ONE verified launcher-ANR dismissal shared across the" +
                " whole test, armed from the first readiness gate, nothing else")

            // 1. Picker entry (bounded scroll), then the mobile start.
            tapByDesc("show-picker")
            tapByDesc("open-mobile-desktop")
            val openTapAt = System.currentTimeMillis()
            val consent = device.wait(Until.findObject(By.textContains("mobile desktop (prototype)")), 3_000)
            if (consent != null) {
                Shot.save("m1-intent-mobile-consent")
                tapByText("Download and start")
            } else {
                // Consent is the ONLY route to openDesktop on a rootfs without
                // the mobile shell. Unobserved consent there means the
                // tap-to-consent chain is broken; the old flow then burned the
                // full 15-min liveness budget blind (run 41510706613).
                // Terminate HERE with explicit evidence. The hierarchy capture
                // carries the picker's visible log text when the app window is
                // in the a11y tree - no product change.
                if (!GuestMobileShell.hasMobileShell(DistroInstaller(ctx).rootfsDir(Distros.UBUNTU))) {
                    captureFailureEvidence("consent-gap")
                    org.junit.Assert.fail("expected first-start consent unobserved within 3s after the" +
                        " open-mobile tap on a rootfs WITHOUT the mobile shell (needsProvision=true):" +
                        " the tap-to-consent chain is broken; evidence captured (shot + focus +" +
                        " dumpsys + UI hierarchy) - NOT continuing into a blind liveness wait")
                }
                Log.i(TAG, "m1-stage: no consent dialog - mobile shell already provisioned" +
                    " (direct-start route); continuing to liveness wait")
            }

            var proc: Process? = null
            val deadline = System.currentTimeMillis() + START_BUDGET_MS
            while (System.currentTimeMillis() < deadline) {
                proc = XServer.sessionProcess
                if (proc != null && proc.isAlive && prefs.getBoolean("mobile_desktop_active", false)) break
                // Fail fast on a re-presented launcher ANR: detection +
                // evidence only, NO dismissal (the test's ONE shared budget
                // stays exactly as before). Run 41510706613 burned the whole
                // 15-min budget with a re-presented ANR dialog focused at
                // failure - an OBSERVED obstruction, never asserted as the
                // proven cause of the session not starting.
                if (Shot.detectVerifiedLauncherAnr(device)) {
                    captureFailureEvidence("liveness-anr-obstruction")
                    org.junit.Assert.fail("observed obstruction: verified launcher-ANR dialog present" +
                        " during the liveness wait at +" + (System.currentTimeMillis() - openTapAt) +
                        "ms after open tap - detection + evidence only, no dismissal;" +
                        " an observed obstruction, not proof of what blocked session start")
                }
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
            // Evidence BEFORE any action: the post-start capture above plus
            // this pre-arm focus snapshot are recorded before anything clicks.
            Log.i(TAG, "m1-arm: pre-focus-gate arm site REACHED")
            val preArm = xlorieFocusState()
            Log.i(TAG, "m1-obstruction: pre-arm focus focused=" + preArm.second +
                " raw=" + preArm.first)
            if (!preArm.second && Shot.detectVerifiedLauncherAnr(device)) {
                // Shared budget: if the readiness gate already spent the ONE
                // dismissal, this call fails closed on the recurrence.
                dismissVerifiedLauncherAnrOnce("pre-focus-gate")
                if (Shot.detectVerifiedLauncherAnr(device)) {
                    captureFailureEvidence("pre-focus-gate")
                    org.junit.Assert.fail("launcher-ANR dialog present after the test's ONE" +
                        " verified dismissal (persistent or new recurrence - instantaneous" +
                        " detection cannot distinguish) - fail closed (obstruction is the finding)")
                }
            }
            // Bounded focus settle poll: after a dismissal focus may take
            // seconds to land; never manufacture it. A dialog during settle
            // fails closed - the budget is spent.
            var focusRaw = preArm.first
            var xlorieFocused = preArm.second
            val focusDeadline = System.currentTimeMillis() + 30_000
            while (!xlorieFocused && System.currentTimeMillis() < focusDeadline) {
                if (Shot.detectVerifiedLauncherAnr(device)) {
                    org.junit.Assert.fail("launcher-ANR dialog present during focus settle" +
                        " after the ONE dismissal was spent (persistent or new recurrence -" +
                        " instantaneous detection cannot distinguish) - fail closed")
                }
                Thread.sleep(2_000)
                val f = xlorieFocusState()
                focusRaw = f.first; xlorieFocused = f.second
            }
            assertTrue("focused window IS the Xlorie display activity" +
                " (positive component match, no contradiction; raw: $focusRaw)",
                xlorieFocused)
            val log = File(ctx.filesDir, "mobile-desktop.log").let { if (it.isFile) it.readText() else "" }
            assertTrue("guest session log exists", log.isNotEmpty())
            assertFalse("guest session log has no FAIL CLOSED marker", log.contains("FAIL CLOSED"))
            Shot.save("m1-evidence-post-start-screen")

            // Render qualification (production-pair run): generated profile
            // on disk, identity-anchored process args/environment, and the
            // uncovered root via a non-destructive iconify+restore.
            renderQualification(rootPid, openTapAt, launchRootStart!!)

            // 2. Repeat open REUSES the tracked session (same handle).
            //    pressBack is a source-verified no-op in the X view (empty
            //    onBackPressed override) - return via REORDER_TO_FRONT of
            //    the tracked MainActivity, session untouched. Launch-anchor
            //    identity is compared BEFORE and AFTER (existing anchor, NOT
            //    a fresh recapture as authority).
            assertEquals("launch root identity unchanged before return-to-picker",
                launchRootStart, procStartTime(rootPid))
            assertTrue("tracked session alive before return-to-picker", proc!!.isAlive)
            returnToPicker("stage-2-reentry")
            assertEquals("launch root identity unchanged after return-to-picker" +
                " (anchor comparison, not recapture authority)",
                launchRootStart, procStartTime(rootPid))
            assertTrue("tracked session alive after return-to-picker", proc!!.isAlive)
            // Expand ONLY when collapsed: on a preserved instance the panel
            // can be expanded already, and toggling show-picker then would
            // COLLAPSE it (run 41483779768 evidence). Neither/contradictory
            // state fails closed.
            when (pickerUiState()) {
                PickerUiState.COLLAPSED -> tapByDesc("show-picker")
                PickerUiState.EXPANDED ->
                    Log.i(TAG, "m1-nav: picker already expanded at stage-2 - no toggle")
                else -> {
                    captureFailureEvidence("stage-2-reentry")
                    org.junit.Assert.fail("picker UI state=" + pickerUiState() +
                        " at stage-2 reentry - evidence captured, no blind toggle")
                }
            }
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
