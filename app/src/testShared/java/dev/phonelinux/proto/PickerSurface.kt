package dev.phonelinux.proto

/** Surface classification for navigating back to the mobile-desktop picker.
 *  Lives in testShared (wired into the test + androidTest source sets in
 *  app/build.gradle.kts): instrumentation support ONLY, no production
 *  surface or behavior change. Exact-token, allowlist-based - no substring
 *  matching, so malformed or foreign-suffix components can never pass.
 *  Navigation authority (the REORDER_TO_FRONT action) requires a POSITIVE
 *  X-activity component; lorieView alone never grants it:
 *  - X component (with or without the lorie node)            -> IN_X_VIEW
 *  - picker component WITHOUT lorieView                      -> PICKER_COMPONENT
 *  - picker component WITH lorieView (contradictory signals) -> AMBIGUOUS
 *  - anything else (foreign/dialog/unreadable/lorie+foreign) -> WRONG_SURFACE
 *  AMBIGUOUS and WRONG_SURFACE both fail closed: no scroll, no click,
 *  no navigation. */
internal enum class PickerSurface { PICKER_COMPONENT, IN_X_VIEW, AMBIGUOUS, WRONG_SURFACE }

private val X_COMPONENTS = setOf("dev.phonelinux.proto/com.termux.x11.MainActivity")
private val PICKER_COMPONENTS = setOf(
    "dev.phonelinux.proto/dev.phonelinux.proto.MainActivity",
    "dev.phonelinux.proto/.MainActivity",
)

internal fun classifyPickerSurface(
    focusedComponent: String?,
    lorieViewPresent: Boolean,
): PickerSurface {
    val c = focusedComponent ?: return PickerSurface.WRONG_SURFACE
    return when {
        c in X_COMPONENTS -> PickerSurface.IN_X_VIEW
        c in PICKER_COMPONENTS && !lorieViewPresent -> PickerSurface.PICKER_COMPONENT
        c in PICKER_COMPONENTS -> PickerSurface.AMBIGUOUS
        else -> PickerSurface.WRONG_SURFACE
    }
}
