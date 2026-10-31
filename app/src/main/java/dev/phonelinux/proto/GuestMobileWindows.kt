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
 *  jwm 2.4.3-1build2 BUG (hint.c ReadWindowState): ClientState.windowType
 *  is left UNINITIALIZED when the client sets no _NET_WM_WINDOW_TYPE, so
 *  <Type>normal</Type> matching is nondeterministic for type-less
 *  clients (xterm) - run 41186745767: xterm maximized at scale 1.0 but
 *  cascaded at 0.75 with the identical rule; reproduced locally under
 *  Xvfb. Fixed upstream post-2.4.3 (master hint.c defaults
 *  windowType=WINDOW_TYPE_NORMAL) but noble ships the broken build.
 *  Mitigation below: explicit <Class> group for known type-less clients
 *  (Class matching reads WM_CLASS, always set) alongside the Type rule.
 *
 *  Tray buttons in 2.4.3 accept ONLY exec:/root:/showdesktop actions
 *  (action.c AddAction; nextstacked/prevstacked exist as Key bindings,
 *  not touch-reachable) - so the Home affordance below uses showdesktop
 *  and per-task switching stays in the TaskList.
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
        // Type rule covers conforming clients (GTK/Qt set the type atom);
        // the Class group deterministically covers type-less xterm while
        // noble's jwm carries the uninitialized-windowType build.
        return "<Group><Type>normal</Type><Option>maximized</Option></Group>" +
            "<Group><Class>XTerm</Class><Option>maximized</Option></Group>"
    }

    /** xterm launch geometry argument: fixed startup geometry only in
     *  floating mode. In maximized mode the Group rule owns the final
     *  size; dropping the fixed geometry avoids a small-window flash
     *  before jwm maximizes on map. Leading space included when set. */
    fun xtermGeometryArg(ui: MobileUiPrefs): String =
        if (ui.windowMode == "maximized") "" else " -geometry 100x30+40+120"

    /** Home affordance: tray button running jwm's showdesktop action
     *  (iconify all clients; a TaskList tap raises one back). Button
     *  height follows the already touch-scaled tray height. */
    fun jwmTrayHome(): String =
        """<TrayButton label="Home">showdesktop</TrayButton>"""
}
