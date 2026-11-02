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
    // run 41490601274 carried two mCurrentFocus lines from two displays) ----

    private val launcherToken = "com.google.android.apps.nexuslauncher/.NexusLauncherActivity"

    private fun twoDisplayDump(display0: String?, display1: String?): String {
        fun focusLine(token: String?) =
            if (token == null) "  mCurrentFocus=null" else "  mCurrentFocus=Window{abc123 u0 " + token + "}"
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

    @Test fun `run-7 transition shape - launcher on interaction display, app on display 1 - is NOT picker`() {
        val dump = twoDisplayDump(launcherToken, devShortComponent)
        assertEquals(launcherToken, interactionFocusToken(dump))
        assertEquals(PickerSurface.WRONG_SURFACE,
            classifyPickerSurface(interactionFocusToken(dump), false))
    }

    @Test fun `interaction display section missing or unfocused is unknown - never a neighbor display`() {
        assertEquals(null, interactionFocusToken(twoDisplayDump(null, devComponent)))
        val onlyDisplay1 = "Display: mDisplayId=1\n  mCurrentFocus=Window{abc123 u0 " + devComponent + "}\n"
        assertEquals(null, interactionFocusToken(onlyDisplay1))
    }

    @Test fun `legacy headerless dump - exactly one distinct token wins, conflicting tokens are unknown`() {
        val one = "  mCurrentFocus=Window{abc123 u0 " + devComponent + "}\n"
        assertEquals(devComponent, interactionFocusToken(one))
        val two = "  mCurrentFocus=Window{abc123 u0 " + launcherToken + "}\n" +
            "  mCurrentFocus=Window{def456 u0 " + devComponent + "}\n"
        assertEquals(null, interactionFocusToken(two))
        val sameTwice = "  mCurrentFocus=Window{abc123 u0 " + devComponent + "}\n" +
            "  mCurrentFocus=Window{def456 u0 " + devComponent + "}\n"
        assertEquals(devComponent, interactionFocusToken(sameTwice))
    }

    @Test fun `malformed focus lines and empty dumps are unknown`() {
        assertEquals(null, interactionFocusToken(""))
        assertEquals(null, interactionFocusToken("Display: mDisplayId=0\n  mCurrentFocus=null\n"))
        assertEquals(null, interactionFocusToken(
            "Display: mDisplayId=0\n  mCurrentFocus=Window{abc123 no-user-token}\n"))
    }
}
