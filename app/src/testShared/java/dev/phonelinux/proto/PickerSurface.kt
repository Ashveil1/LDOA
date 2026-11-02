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
 *  field (DisplayContent.mCurrentFocus, line 571), dumped once per display
 *  inside a "Display: mDisplayId=N" section (header line 3585, focus line
 *  3619) - a dump can therefore carry SEVERAL mCurrentFocus lines, and line
 *  ORDER is never authority. HYPOTHESIS, not archive-proven: run
 *  41490601274's truncated (300-char) excerpts lack section headers, so
 *  which display each of its lines belonged to is UNKNOWN - per-display
 *  sections are the AOSP-established possible structure, not a confirmed
 *  run-7 fact. Interaction scoping: UiAutomator 2.3.0 (repo dependency)
 *  builds MotionEvents without a displayId, and AOSP InputDispatcher
 *  injects unspecified-display events to the DEFAULT display (id 0) - no
 *  displayId is set anywhere in the test or workflow, so display 0 is the
 *  interaction display. Section integrity: only a UNIQUE valid focus
 *  observation in a section is a token - a repeated identical token
 *  confirms, but a conflicting token, a null focus line, or an
 *  unparseable one (e.g. a dialog window title) poisons the whole section
 *  to UNKNOWN; an earlier valid token never stays authoritative over a
 *  later conflicting/invalid line. Legacy header-less dumps fall back to
 *  "exactly one distinct valid token"; conflicting legacy tokens are
 *  UNKNOWN (null), never first-line. */
internal const val INTERACTION_DISPLAY_ID = 0

private val DISPLAY_HEADER = Regex("^\\s*Display: mDisplayId=(\\d+)")
private val FOCUS_LINE = Regex("mCurrentFocus=")
private val FOCUS_WINDOW =
    Regex("mCurrentFocus=Window\\{[^}]*\\bu0\\s+([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)")

/** Every display section that carried at least one mCurrentFocus line,
 *  mapped to its UNIQUE valid token - or null when the section's focus
 *  observations are absent/conflicting/null/malformed. A display with a
 *  header but no focus line is absent from the map. */
internal fun parseFocusedTokensByDisplay(dump: String): Map<Int, String?> {
    val tokens = LinkedHashMap<Int, String?>()
    var display: Int? = null
    for (line in dump.lineSequence()) {
        val header = DISPLAY_HEADER.find(line)
        if (header != null) { display = header.groupValues[1].toIntOrNull(); continue }
        if (!FOCUS_LINE.containsMatchIn(line)) continue
        val d = display ?: continue
        val token = FOCUS_WINDOW.find(line)?.groupValues?.get(1)
        val prior: String? = if (tokens.containsKey(d)) tokens[d] else null
        tokens[d] = when {
            token == null -> null                       // null/malformed poisons
            prior == null && tokens.containsKey(d) -> null // already poisoned
            prior == null -> token                      // first observation
            prior == token -> token                     // identical repeat confirms
            else -> null                                // conflict poisons
        }
    }
    return tokens
}

private fun hasDisplayHeaders(dump: String): Boolean =
    dump.lineSequence().any { DISPLAY_HEADER.find(it) != null }

/** Focus token of the INTERACTION display only; null when the dump has no
 *  unique valid token for that display (transition/absent/conflicting/
 *  malformed/failed) or a legacy header-less dump carries conflicting
 *  tokens - callers treat null as UNVERIFIED and never as a surface
 *  verdict. */
internal fun interactionFocusToken(
    dump: String,
    displayId: Int = INTERACTION_DISPLAY_ID,
): String? {
    if (hasDisplayHeaders(dump)) {
        return parseFocusedTokensByDisplay(dump)[displayId]
    }
    // Legacy header-less dumps: SAME poison rule as sections - EVERY focus
    // line must be a valid token (a null/malformed/dialog-title line is
    // UNKNOWN even when a valid token coexists) and exactly one distinct
    // token must result; anything else is unknown, never first-line.
    val observations = dump.lineSequence()
        .filter { FOCUS_LINE.containsMatchIn(it) }
        .map { FOCUS_WINDOW.find(it)?.groupValues?.get(1) }
        .toList()
    if (observations.any { it == null }) return null
    return observations.distinct().singleOrNull()
}

/** Temporal stability decision for the interaction-display focus poll.
 *  Pure: the androidTest loop feeds (elapsed monotonic ms, token) samples.
 *  The deadline is checked BEFORE accepting a result - a sample taken at
 *  or past the budget can never confirm. An unreadable (null) sample
 *  RESETS the streak; a changed token restarts it; only the SAME exact
 *  non-null token on two consecutive in-budget samples confirms. */
internal sealed class StabilityOutcome {
    data class Pending(val lastToken: String?) : StabilityOutcome()
    data class Confirmed(val token: String) : StabilityOutcome()
    object Exhausted : StabilityOutcome()
}

internal fun reduceStabilitySample(
    lastToken: String?,
    elapsedMs: Long,
    token: String?,
    budgetMs: Long,
): StabilityOutcome {
    if (elapsedMs >= budgetMs) return StabilityOutcome.Exhausted
    if (token == null) return StabilityOutcome.Pending(null)
    if (token == lastToken) return StabilityOutcome.Confirmed(token)
    return StabilityOutcome.Pending(token)
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
