package dev.phonelinux.proto

import org.junit.Assert.assertEquals
import org.junit.Test

class PickerSurfaceTest {
    private val devComponent = "dev.phonelinux.proto/dev.phonelinux.proto.MainActivity"
    private val devShortComponent = "dev.phonelinux.proto/.MainActivity"
    private val xComponent = "dev.phonelinux.proto/com.termux.x11.MainActivity"

    @Test fun `exact picker component without lorie view is picker`() {
        assertEquals(PickerSurface.PICKER_COMPONENT, classifyPickerSurface(devComponent, false))
        assertEquals(PickerSurface.PICKER_COMPONENT, classifyPickerSurface(devShortComponent, false))
    }

    @Test fun `exact x component is x view with or without lorie node`() {
        assertEquals(PickerSurface.IN_X_VIEW, classifyPickerSurface(xComponent, false))
        assertEquals(PickerSurface.IN_X_VIEW, classifyPickerSurface(xComponent, true))
    }

    @Test fun `lorie view under picker focus is ambiguous - never picker, never scroll`() {
        assertEquals(PickerSurface.AMBIGUOUS, classifyPickerSurface(devComponent, true))
        assertEquals(PickerSurface.AMBIGUOUS, classifyPickerSurface(devShortComponent, true))
    }

    @Test fun `lorie view alone grants no navigation authority - foreign or unreadable focus stays wrong surface`() {
        assertEquals(PickerSurface.WRONG_SURFACE,
            classifyPickerSurface("com.google.android.apps.nexuslauncher/.NexusLauncherActivity", true))
        assertEquals(PickerSurface.WRONG_SURFACE, classifyPickerSurface(null, true))
    }

    @Test fun `foreign surfaces are wrong surface - launcher, ime, dialogs`() {
        assertEquals(PickerSurface.WRONG_SURFACE,
            classifyPickerSurface("com.google.android.apps.nexuslauncher/.NexusLauncherActivity", false))
        assertEquals(PickerSurface.WRONG_SURFACE,
            classifyPickerSurface("com.google.android.inputmethod.latin/.LatinIME", false))
        assertEquals(PickerSurface.WRONG_SURFACE,
            classifyPickerSurface("com.google.android.permissioncontroller/...GrantPermissionsActivity", false))
    }

    @Test fun `malformed and suffix-attack components never match the allowlist`() {
        assertEquals(PickerSurface.WRONG_SURFACE,
            classifyPickerSurface("evil.example/dev.phonelinux.proto/.MainActivity", false))
        assertEquals(PickerSurface.WRONG_SURFACE,
            classifyPickerSurface("dev.phonelinux.proto/dev.phonelinux.proto.MainActivityExtra", false))
        assertEquals(PickerSurface.WRONG_SURFACE,
            classifyPickerSurface("xdev.phonelinux.proto/com.termux.x11.MainActivity", false))
    }

    @Test fun `unreadable focus is wrong surface - fail closed, never a match`() {
        assertEquals(PickerSurface.WRONG_SURFACE, classifyPickerSurface(null, false))
        assertEquals(PickerSurface.WRONG_SURFACE, classifyPickerSurface("", false))
    }

    @Test fun `picker ui collapsed - show-picker only`() {
        assertEquals(PickerUiState.COLLAPSED, classifyPickerUi(true, false))
    }

    @Test fun `picker ui expanded - open-mobile-desktop only, no toggle needed`() {
        assertEquals(PickerUiState.EXPANDED, classifyPickerUi(false, true))
    }

    @Test fun `picker ui neither - fail closed`() {
        assertEquals(PickerUiState.NEITHER, classifyPickerUi(false, false))
    }

    @Test fun `picker ui both markers is contradictory - ambiguous, fail closed`() {
        assertEquals(PickerUiState.AMBIGUOUS, classifyPickerUi(true, true))
    }

    // ---- Per-display focus parse (source: AOSP android14 DisplayContent.dump;
    // run 41490601274 carried multiple mCurrentFocus lines; section structure
    // is AOSP-established, run-7 display assignment is hypothesis) ----

    private val launcherToken = "com.google.android.apps.nexuslauncher/.NexusLauncherActivity"

    private fun focusLine(token: String?) =
        if (token == null) "  mCurrentFocus=null" else "  mCurrentFocus=Window{abc123 u0 " + token + "}"

    private fun twoDisplayDump(display0: String?, display1: String?): String {
        return "Display: mDisplayId=0\n" + focusLine(display0) + "\n" +
            "  mFocusedApp=AppWindowToken{x u0 whatever}\n" +
            "Display: mDisplayId=1\n" + focusLine(display1) + "\n"
    }

    @Test fun `multi-display dump scopes to the interaction display - display 0 wins, display 1 ignored`() {
        val dump = twoDisplayDump(devComponent, launcherToken)
        assertEquals(mapOf(0 to devComponent, 1 to launcherToken), parseFocusedTokensByDisplay(dump))
        assertEquals(devComponent, interactionFocusToken(dump))
        assertEquals(PickerSurface.PICKER_COMPONENT,
            classifyPickerSurface(interactionFocusToken(dump), false))
    }

    @Test fun `run-7 shape - launcher on interaction display, app on display 1 - is NOT picker`() {
        val dump = twoDisplayDump(launcherToken, devShortComponent)
        assertEquals(launcherToken, interactionFocusToken(dump))
        assertEquals(PickerSurface.WRONG_SURFACE,
            classifyPickerSurface(interactionFocusToken(dump), false))
    }

    @Test fun `realistic preamble before display headers still scopes per display`() {
        val preamble = "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n" +
            "  mOverrideDisplayInfo=...\n\n"
        assertEquals(devComponent, interactionFocusToken(preamble + twoDisplayDump(devComponent, launcherToken)))
        assertEquals(launcherToken, interactionFocusToken(preamble + twoDisplayDump(launcherToken, devComponent)))
    }

    @Test fun `interaction display section missing or unfocused is unknown - never a neighbor display`() {
        assertEquals(null, interactionFocusToken(twoDisplayDump(null, devComponent)))
        val onlyDisplay1 = "Display: mDisplayId=1\n" + focusLine(devComponent) + "\n"
        assertEquals(null, interactionFocusToken(onlyDisplay1))
    }

    @Test fun `conflicting token later in the section poisons an earlier valid token`() {
        val dump = "Display: mDisplayId=0\n" + focusLine(devComponent) + "\n" +
            focusLine(launcherToken) + "\n"
        assertEquals(null, interactionFocusToken(dump))
        assertEquals(mapOf(0 to null), parseFocusedTokensByDisplay(dump))
    }

    @Test fun `null or malformed focus line later in the section poisons an earlier valid token`() {
        val withNull = "Display: mDisplayId=0\n" + focusLine(devComponent) + "\n" + focusLine(null) + "\n"
        assertEquals(null, interactionFocusToken(withNull))
        val withMalformed = "Display: mDisplayId=0\n" + focusLine(devComponent) + "\n" +
            "  mCurrentFocus=Window{916ca84 u0 Application Not Responding: com.google.android.apps.nexuslauncher}\n"
        assertEquals(null, interactionFocusToken(withMalformed))
    }

    @Test fun `repeated identical token within a section stays valid`() {
        val dump = "Display: mDisplayId=0\n" + focusLine(devComponent) + "\n" +
            focusLine(devComponent) + "\n"
        assertEquals(devComponent, interactionFocusToken(dump))
    }

    @Test fun `repeated display section with conflicting content is unknown`() {
        val dump = twoDisplayDump(devComponent, null) + "Display: mDisplayId=0\n" + focusLine(launcherToken) + "\n"
        assertEquals(null, interactionFocusToken(dump))
    }

    @Test fun `legacy headerless dump - exactly one distinct token wins, conflicting tokens are unknown`() {
        val one = focusLine(devComponent) + "\n"
        assertEquals(devComponent, interactionFocusToken(one))
        val two = focusLine(launcherToken) + "\n" + focusLine(devComponent) + "\n"
        assertEquals(null, interactionFocusToken(two))
        val sameTwice = focusLine(devComponent) + "\n" + focusLine(devComponent) + "\n"
        assertEquals(devComponent, interactionFocusToken(sameTwice))
    }

    @Test fun `legacy headerless - null or malformed focus line poisons a coexisting valid token, both orders`() {
        val anrDialog = "  mCurrentFocus=Window{916ca84 u0 Application Not Responding: com.google.android.apps.nexuslauncher}\n"
        assertEquals(null, interactionFocusToken(focusLine(devComponent) + "\n  mCurrentFocus=null\n"))
        assertEquals(null, interactionFocusToken("  mCurrentFocus=null\n" + focusLine(devComponent) + "\n"))
        assertEquals(null, interactionFocusToken(focusLine(devComponent) + "\n" + anrDialog))
        assertEquals(null, interactionFocusToken(anrDialog + focusLine(devComponent) + "\n"))
    }

    @Test fun `malformed focus lines and empty dumps are unknown`() {
        assertEquals(null, interactionFocusToken(""))
        assertEquals(null, interactionFocusToken("Display: mDisplayId=0\n  mCurrentFocus=null\n"))
        assertEquals(null, interactionFocusToken(
            "Display: mDisplayId=0\n  mCurrentFocus=Window{abc123 no-user-token}\n"))
    }

    // ---- Frozen LAST-ANR replay (AOSP saveANRStateLocked: a full
    // `dumpsys window` after any ANR carries a FROZEN display-contents
    // replay at the top until its lifetime expires - run 41495139793) ----

    private fun fullDumpWithFrozenAnr(frozenToken: String, liveToken: String?): String =
        "WINDOW MANAGER LAST ANR (dumpsys window lastanr)\n" +
        "  ANR time: Nov 2, 2026 5:54:59 PM\n" +
        "  Application at fault: ActivityRecord{7a97997 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity}\n" +
        "  Reason: Input dispatching timed out\n\n" +
        "Last ANR continued\n" +
        "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n" +
        "Display: mDisplayId=0 (organized)\n" + focusLine(frozenToken) + "\n" +
        "  mFocusedApp=ActivityRecord{7a97997 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t7}\n\n" +
        "WINDOW MANAGER POLICY STATE (dumpsys window policy)\n" +
        "    mSafeMode=false\n\n" +
        "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n" +
        "Display: mDisplayId=0 (organized)\n" + focusLine(liveToken) + "\n" +
        "  mFocusedApp=ActivityRecord{2f3757a u0 dev.phonelinux.proto/.MainActivity t8}\n\n" +
        "WINDOW MANAGER TOKENS (dumpsys window tokens)\n"

    @Test fun `frozen last-ANR replay never poisons or outranks the live display section`() {
        val dump = fullDumpWithFrozenAnr(launcherToken, devComponent)
        assertEquals(devComponent, interactionFocusToken(dump))
        assertEquals(PickerSurface.PICKER_COMPONENT,
            classifyPickerSurface(interactionFocusToken(dump), false))
    }

    @Test fun `frozen replay with live launcher focus still classifies launcher`() {
        val dump = fullDumpWithFrozenAnr(devComponent, launcherToken)
        assertEquals(launcherToken, interactionFocusToken(dump))
    }

    @Test fun `frozen replay with unfocused live display is unknown`() {
        assertEquals(null, interactionFocusToken(fullDumpWithFrozenAnr(launcherToken, null)))
    }

    @Test fun `no-ANR last-anr section does not eat the live dump`() {
        // Real shape: the no-ANR line is always followed by the live
        // WINDOW MANAGER DISPLAY CONTENTS section header.
        val dump = "WINDOW MANAGER LAST ANR (dumpsys window lastanr)\n" +
            "  <no ANR has occurred since boot>\n\n" +
            "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n" +
            twoDisplayDump(devComponent, launcherToken)
        assertEquals(devComponent, interactionFocusToken(dump))
    }

    @Test fun `frozen-only or truncated input is unknown - never the frozen focus token`() {
        // Marker present but the live display section never comes (truncated
        // dump / ANR replay only): the frozen token must NOT leak out.
        val frozenOnly = "WINDOW MANAGER LAST ANR (dumpsys window lastanr)\n" +
            "  ANR time: Nov 2, 2026 5:54:59 PM\n" +
            "Last ANR continued\n" +
            "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n" +
            "Display: mDisplayId=0 (organized)\n" + focusLine(launcherToken) + "\n"
        assertEquals(null, interactionFocusToken(frozenOnly))
        val truncatedMidFrozen = fullDumpWithFrozenAnr(launcherToken, devComponent)
            .substring(0, 200)
        assertEquals(null, interactionFocusToken(truncatedMidFrozen))
    }

    // ---- Stability poll reducer (pure temporal logic) ----

    private val budget = 3_000L

    @Test fun `stability - same exact token twice in budget confirms`() {
        val first = reduceStabilitySample(null, 0, devComponent, budget)
        assertEquals(StabilityOutcome.Pending(devComponent), first)
        val second = reduceStabilitySample((first as StabilityOutcome.Pending).lastToken, 400, devComponent, budget)
        assertEquals(StabilityOutcome.Confirmed(devComponent), second)
    }

    @Test fun `stability - null sample resets the streak`() {
        val first = reduceStabilitySample(null, 0, devComponent, budget)
        assertEquals(StabilityOutcome.Pending(devComponent), first)
        val reset = reduceStabilitySample(devComponent, 400, null, budget)
        assertEquals(StabilityOutcome.Pending(null), reset)
        val restart = reduceStabilitySample(null, 800, devComponent, budget)
        assertEquals(StabilityOutcome.Pending(devComponent), restart)
    }

    @Test fun `stability - changing tokens never confirm`() {
        var last: String? = null
        val tokens = listOf(launcherToken, devComponent, launcherToken, devComponent)
        tokens.forEachIndexed { i, t ->
            val out = reduceStabilitySample(last, i * 400L, t, budget)
            assertEquals(StabilityOutcome.Pending(t), out)
            last = (out as StabilityOutcome.Pending).lastToken
        }
    }

    @Test fun `stability - sample at or past the budget can never confirm`() {
        assertEquals(StabilityOutcome.Exhausted,
            reduceStabilitySample(devComponent, budget, devComponent, budget))
        assertEquals(StabilityOutcome.Exhausted,
            reduceStabilitySample(devComponent, budget + 1, devComponent, budget))
    }

    @Test fun `stability - last in-budget null sample then exhaustion yields no confirmation`() {
        val reset = reduceStabilitySample(devComponent, 2_800, null, budget)
        assertEquals(StabilityOutcome.Pending(null), reset)
        assertEquals(StabilityOutcome.Exhausted,
            reduceStabilitySample(null, budget, devComponent, budget))
    }
}
