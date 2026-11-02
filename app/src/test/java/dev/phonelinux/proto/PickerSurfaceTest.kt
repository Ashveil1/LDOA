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
}
