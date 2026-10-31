package dev.phonelinux.proto

/** Phone window-behavior layer (parallel custom-desktop work, Ash
 *  2026-10-31 "split the work onto it"): jwm window rules for the mobile
 *  session. App windows open maximized into the tray-respecting work
 *  area (one-app-at-a-time phone semantics; task switching stays in the
 *  existing TaskList); dialogs and utility windows keep natural sizes.
 *
 *  Grounded in the shipped jwm: noble installs jwm 2.4.3-1build2; its
 *  group matching AND-s all patterns in one Group (group.c ApplyGroups),
 *  <Type>normal</Type> restricts the rule to normal-type clients
 *  (group.c windowTypeMapping), and OPTION_MAXIMIZED (group.c) applies
 *  on map. pcmanfm --desktop's surface is _NET_WM_WINDOW_TYPE_DESKTOP,
 *  which jwm does not frame, so the rule never touches the desktop.
 *
 *  Render-path neutral BY DESIGN: window MANAGEMENT semantics only - no
 *  compositor, GL, or driver involvement, and the rules carry over
 *  unchanged under whatever render architecture is selected later. */
object GuestMobileWindows {

    /** jwmrc fragment: maximized-by-default for normal app windows in
     *  the phone window mode; empty in floating mode (Phase A behavior:
     *  windows keep their startup geometry). Static Group rules are
     *  order-independent to jwm; the shell inserts this between the
     *  style block and the Tray definition. */
    fun jwmWindowRules(ui: MobileUiPrefs): String {
        if (ui.windowMode != "maximized") return ""
        return "<Group><Type>normal</Type><Option>maximized</Option></Group>"
    }

    /** xterm launch geometry argument: fixed startup geometry only in
     *  floating mode. In maximized mode the Group rule owns the final
     *  size; dropping the fixed geometry avoids a small-window flash
     *  before jwm maximizes on map. Leading space included when set. */
    fun xtermGeometryArg(ui: MobileUiPrefs): String =
        if (ui.windowMode == "maximized") "" else " -geometry 100x30+40+120"
}
