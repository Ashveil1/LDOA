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


/** Per-display focus parse of a `dumpsys window` dump. Source-grounded
 *  (AOSP android14-release DisplayContent.java): focus is a PER-DISPLAY
 *  field (DisplayContent.mCurrentFocus), dumped once per display inside a
 *  "Display: mDisplayId=N" section ("  mCurrentFocus=Window{... u0 pkg/Class}")
 *  - a dump can therefore carry SEVERAL mCurrentFocus lines naming different
 *  apps on different displays, and line ORDER is never authority (run
 *  41490601274: a transition dump carried launcher + dev MainActivity and
 *  first-line order misclassified the surface). UiDevice input injection
 *  targets the default display (id 0), where the app runs and taps land;
 *  interaction reads are scoped to that section only. A legacy dump with no
 *  display headers falls back to "exactly one distinct token"; conflicting
 *  legacy tokens are UNKNOWN (null), never first-line. */
internal const val INTERACTION_DISPLAY_ID = 0

private val DISPLAY_HEADER = Regex("^\\s*Display: mDisplayId=(\\d+)")
private val FOCUS_WINDOW =
    Regex("mCurrentFocus=Window\\{[^}]*\\bu0\\s+([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)")

internal fun parseFocusedTokensByDisplay(dump: String): Map<Int, String> {
    val tokens = LinkedHashMap<Int, String>()
    var display: Int? = null
    for (line in dump.lineSequence()) {
        val header = DISPLAY_HEADER.find(line)
        if (header != null) { display = header.groupValues[1].toIntOrNull(); continue }
        val focus = FOCUS_WINDOW.find(line) ?: continue
        val d = display ?: continue
        if (!tokens.containsKey(d)) tokens[d] = focus.groupValues[1]
    }
    return tokens
}

/** Focus token of the INTERACTION display only; null when the dump has no
 *  parseable token for that display (transition/absent/failed) or a legacy
 *  header-less dump carries conflicting tokens - callers treat null as
 *  UNVERIFIED and never as a surface verdict. */
internal fun interactionFocusToken(
    dump: String,
    displayId: Int = INTERACTION_DISPLAY_ID,
): String? {
    if (DISPLAY_HEADER.containsMatchIn(dump)) {
        return parseFocusedTokensByDisplay(dump)[displayId]
    }
    return FOCUS_WINDOW.findAll(dump).map { it.groupValues[1] }.distinct().singleOrNull()
}

/** Collapsed/expanded state of the picker panel on the VERIFIED picker
 *  component. The panel state survives in the preserved MainActivity
 *  instance: returning from the X view can find it already expanded, and
 *  toggling show-picker then would COLLAPSE it - never toggle blindly.
 *  Both markers at once is contradictory (AMBIGUOUS); neither means the
 *  picker content is absent (NEITHER). Both fail closed. */
internal enum class PickerUiState { COLLAPSED, EXPANDED, NEITHER, AMBIGUOUS }

internal fun classifyPickerUi(
    showPickerPresent: Boolean,
    openMobileDesktopPresent: Boolean,
): PickerUiState = when {
    showPickerPresent && openMobileDesktopPresent -> PickerUiState.AMBIGUOUS
    openMobileDesktopPresent -> PickerUiState.EXPANDED
    showPickerPresent -> PickerUiState.COLLAPSED
    else -> PickerUiState.NEITHER
}
