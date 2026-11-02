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
import androidx.test.uiautomator.UiDevice
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
        fresh.click()
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
        for (tool in listOf("xdotool", "xwininfo", "xprop"))
            assertTrue("render tooling: guest $tool present (provisioning gap if not)",
                File(rootfs, "usr/bin/$tool").isFile)

        // 6. Geometry ground truth: POSITIVE root dims, parser-verified.
        val rootStats = RenderProbeParse.winStats(guest("xwininfo", "-root"))
        assertNotNull("render/geometry: root stats parseable with positive dims", rootStats)
        val rs = rootStats!!

        // 7. Desktop window: linked to the tracked pcmanfm PROCESS via
        //    _NET_WM_PID; class Pcmanfm; mapped; covers the root (actual
        //    intersection, not raw w*h). Without the link/coverage a passing
        //    band could be xsetroot only - painter indistinguishable = fail.
        val pcmanCandidates = (RenderProbeParse.searchIds(guest("xdotool", "search", "--class", "pcmanfm"))
            ?: throw AssertionError("render/geometry: pcmanfm search output unparseable")) +
            (RenderProbeParse.searchIds(guest("xdotool", "search", "--class", "Pcmanfm"))
            ?: throw AssertionError("render/geometry: Pcmanfm search output unparseable"))
        val deskLinked = pcmanCandidates.distinct().filter { id ->
            RenderProbeParse.xpropPid(guest("xprop", "-id", id.toString(), "_NET_WM_PID", "WM_CLASS"))
                ?.toInt() == pcmanPid
        }
        assertTrue("render/geometry: exactly one pcmanfm window owned by tracked pid $pcmanPid" +
            " (candidates=${pcmanCandidates.size}, linked=${deskLinked.size})", deskLinked.size == 1)
        val deskId = deskLinked[0]
        val deskClass = RenderProbeParse.xpropClass(guest("xprop", "-id", deskId.toString(), "WM_CLASS"))
        assertEquals("render/geometry: desktop window class", "Pcmanfm", deskClass?.second)
        val deskStats0 = RenderProbeParse.winStats(guest("xwininfo", "-id", deskId.toString()))
        assertNotNull("render/geometry: desktop window stats parseable", deskStats0)
        val ds = deskStats0!!
        assertEquals("render/geometry: desktop window MAPPED", "IsViewable", ds.mapState)
        val iw = maxOf(0, minOf(ds.absX + ds.w, rs.w) - maxOf(ds.absX, 0))
        val ih = maxOf(0, minOf(ds.absY + ds.h, rs.h) - maxOf(ds.absY, 0))
        Log.i(TAG, "render-evidence: desktop window " + ds + " vs root " + rs.w + "x" + rs.h)
        assertTrue("render/geometry: desktop window covers >=80% of the root" +
            " (intersection ${iw}x${ih} of ${rs.w}x${rs.h})", iw.toLong() * ih >= rs.w.toLong() * rs.h * 4 / 5)

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
