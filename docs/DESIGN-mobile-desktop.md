# LDOA mobile desktop - scope plan (for review, not all approved)

Status legend: [APPROVED+CODED] Ash approved 2026-10-29 16:48, code in this
branch / [REQUESTED] Ash asked for the outcome, implementation NOT
approved / [DEFERRED] proposed, not requested or approved yet.

Source of the direction (WhatsApp, 2026-10-29):
- 16:40 Ash: split a parallel team to build our own desktop ("แยกทีมไปทำ
  desktop เองเลย"); 16:48 "ใช่" accepted the concrete Phase A offer.
- 16:48-16:49 UX criteria: Android/iOS-like press/hold/drag/scroll,
  customizable screen/sizes, iOS-like gesture navigation (no Android
  3-button nav), animations.
- 17:26-17:32 launcher direction: use LDOA all the time as the phone's
  home, opening BOTH Android apps and Linux programs; add this AT THE
  SWITCHOVER to the new desktop ("ค่อยเพิ่มตอนเปลี่ยนเป็น desktop ใหม่"),
  not in the trial build.

## Phase A [APPROVED+CODED]

Additive mobile desktop mode on the SAME Ubuntu rootfs; GNOME retained as
primary. Reused jwm (transparently), our session design: touch-sized
tray/fonts, Xft.dpi from device density, panel size/position, background.
Files: GuestMobileShell.kt, MobileModeSettings.kt, MobileShellSpikeTest.kt,
mobile-desktop.yml (dispatch-only). Interface seam (XServer mode param,
stopDesktopMode, picker entry) is the GNOME worker's, after the trial APK.

## Deferred extras [DEFERRED - re-propose after Phase A evidence]

Compose app drawer from guest .desktop files, wmctrl window switcher,
session controls (MobileLauncher.kt). NOT approved; Ash's Phase A go does
not cover these.

## Phase B: iOS-like navigation + animations [REQUESTED, unapproved]

Grounded limits (developer.android.com/develop/ui/views/touch-and-input/
gestures/gesturenav): back-gesture edge regions can only be selectively
excluded in LIMITED rects; home/quick-switch bottom gestures CANNOT be
opted out. So: an in-app reserved gesture zone (handle strip rendered by
a lorie-side patch), never fighting OS edges; every action duplicated as
a button (accessible fallback). Back inside Linux apps has no universal
meaning - configurable Escape/Alt+Left mapping only. Animations: noble
picom 10.2 gives fades/opacity only, NOT iOS-style window motion; real
motion needs a third-party compositor fork (new supply-chain decision) -
offer fades default-OFF + reduced-motion toggle first.

## Phase C: Android Home mode at the desktop switchover [REQUESTED 17:32,
   for later review - belongs to the switchover, NOT the trial build]

- Qualify: manifest home activity (MAIN+HOME). Request: RoleManager
  .createRequestRoleIntent(ROLE_HOME) - SYSTEM dialog, Ash consents; the
  app cannot self-grant (developer.android.com/reference/android/app/
  role/RoleManager).
- Home screen (Compose, ours): Android launchable apps + Linux .desktop
  entries in one grid. App-list visibility: evaluate LauncherApps /
  intent-query with home-role privileges BEFORE reaching for
  QUERY_ALL_PACKAGES (developer.android.com/training/package-visibility).
- Launch behavior: Android apps open as Android apps (platform
  multi-window exists - not forced fullscreen); Linux apps open the X
  session and start inside it. Two worlds, one home screen - Android
  apps never become Linux windows.
- Boot: as default home, unlock lands on LDOA - but the lock screen
  still shows first, and "opens on EVERY unlock" is not guaranteed
  (the system can resume the prior app). No always-foreground promise.
- System gestures (home/quick-switch/back) remain the OS's.

## Standing constraints

Early trial APK ships first (GNOME track); mobile mode never gates it.
No APK readiness claims from the dispatch lane alone. ARM execution is
unproven everywhere until real-device evidence exists.

## Direction note 2026-10-29 19:07 (Ash, verbatim intent): finger-first
   input/control surface

Ash's raw direction (WhatsApp wamid...Q0ZCOUY3QkVFMDYxQUI4MkU2RTZB
QkIyNzVDRDJERQA, paraphrase boundary marked): Termux:X11's built-in
controls (mouse, scroll mechanisms, toolbars) do not serve finger-first
use, and some finger gestures are unusable. Conditional authority: IF
the existing input layer cannot be fixed, THEN rebuild the remaining
control/input layers while KEEPING Termux:X11's display/stability
parts ("เอาส่วนแสดงผลและความเสถียรของ termux x11 มา แต่ส่วนที่เหลือ
แก้เองให้เข้า").

Recorded scope implications for the mobile desktop plan:

- The custom shell's touch control surface is the primary path: legacy
  mouse/toolbar style controls become OPTIONAL, not default clutter.
- The display/render path (vendored lorie X server, composition) stays;
  the rebuild candidate is the CONTROL and INPUT-GESTURE layer only.
  No X server rewrite is authorized by this.
- CONDITIONAL: a rebuild of the input layer is authorized to be PLANNED
  only after the fix path on the existing vendored input emulation is
  proven to hit a limit (2-finger right-click currently UNSOLVED after
  7c8a9f4's falsified hypothesis; legacy scroll still unproven). No
  blanket "implement all gestures" grant.
- Input-layer work itself belongs to the app/input worker lane; this
  doc only carries the requirement for the frontend shell plan and the
  coordinated boundary.

## Direction note 2026-10-29 19:11 (Ash, verbatim intent): design north
   star

Ash's raw direction (WhatsApp wamid...QzdBRjI2OUJBQ0I1M0M0RjJBRkRE
MjczMzY2RTI4RAA): usage should FEEL like Ubuntu Touch / Android / iOS,
but everything underneath stays FULL Linux desktop capability. Visual
beauty like iOS is the PRIMARY bar. Stock Termux:X11 control UI cannot
deliver that as the final product - which is why it cannot be used
as-is as the end state.

Recorded scope implications:

- NORTH STAR: finger-first mobile usability (Ubuntu Touch class) with
  iOS-grade visual polish, on top of the unchanged full Linux desktop
  capability. No capability cuts, no "mobile = reduced Linux".
- REJECTED as final product: stock Termux:X11 control UI (mouse
  emulation, scroll mechanisms, toolbar clutter). It may remain only
  as an optional legacy layer, never the default experience.
- KEEP: the working display/render core (vendored lorie X server). The
  rejection is of the CONTROL UI, not necessarily the display core.
- Custom surface owns: Home/launcher, window switching, gestures,
  control shell, theming toward iOS-class polish (within what noble
  compositor options actually deliver - see Phase B note: picom gives
  fades/opacity only; real iOS-style window motion needs a compositor
  decision that is still unapproved).
- Per-Linux-app internals may need separate adaptation; no universal
  "every X app becomes finger-perfect" promise.
- Implementation gate unchanged: current approved Phase A + trial entry
  work first; any bigger build follows read-code -> bounded plan ->
  approval. No full-Linux replacement, no silent capability cut.

## Direction note 2026-10-29 19:17 (Ash): answers to the 4 design
   questions + delegated style judgment

Ash's answers (WhatsApp wamid...QzU1NDhGMDc1NjU4M0MyRjVGMjZFOEVB
QkQxMzg0NAA=, verbatim paraphrase):
1. Window behavior: full Linux flexibility - maximize to fullscreen,
   minimize, multi-window, portrait AND landscape, all supported.
2. Home pages: separate Android and Linux pages is fine; layout
   adjustable in any case.
3. Aesthetics: whatever looks beautiful and "wow"; if Liquid Glass can
   be done beautifully, do it. Style judgment delegated to us.
4. Apps: no specific list; wants ALL apps usable. WAIVES agent-side
   per-app testing at this stage - he will test himself when it is
   done. (Waiver scope: per-app compatibility testing only. Core
   install/entry/controls verification, license, money, and security
   verification are NOT waived.)

## Cohesive plan v2 (post-19:17 answers) - ACCEPTED 19:26 by Ash
   ("ตามนั้นเก่งมาก", WhatsApp wamid...QzBCNDAyOUQ0Q0UwOTUxRDBE
   MTc5RENGQjg0OTc4NgA=) via the delivered requirements File
   https://files.instinct.com/frd1ut0pg8th-ldoa (PRIVATE, gen 2).
   Acceptance covers the shown scope/direction incl. the 5-step
   order; it is NOT proof anything is implemented, and implies no
   spending/license/account changes/compositor rewrite. Design
   choices shown there are settled - do not re-ask them.

Design principles (locked by Ash's answers):
- P1 Finger-first, iOS-grade polish is the primary bar; full Linux
  capability untouched underneath.
- P2 Windows behave like a real Linux desktop: fullscreen, minimize,
  multi-window, both orientations - all supported, fullscreen-first
  default on phone-sized screens.
- P3 Home: separate Android and Linux pages, layout user-adjustable.
- P4 Legacy mouse-emulation controls: optional layer, never default.
- P5 Beauty target: Liquid Glass direction if it renders beautifully
  AND performs acceptably; reduce/disable effects if heavy. No
  performance promise - measure, then decide.

Grounded feasibility for P5 (new evidence this round):
- noble picom 10.2 man page (manpages.ubuntu.com/manpages/noble/
  man1/picom.1.html) lists blur-method with dual_kawase available and
  corner-radius VALUE - compositor-level background blur + rounded
  corners + opacity IS feasible with the existing noble package set,
  no new supply chain. iOS-STYLE WINDOW MOTION (spring transitions)
  is still NOT in picom - that remains an unapproved compositor
  decision.
- Performance on Ash's ARM device: UNVERIFIED. dual_kawase blur is
  GPU-fragment-shader work; on lorie's GLES path the cost is unknown
  until measured on real hardware. Plan: ship glass default-ON in the
  custom shell with an effects toggle, capture perf evidence in the
  mobile lane before any "smooth" claim.

Proposed build order (each step its own plan+approval gate, trial
entry work unaffected):
1. Phase A (approved): additive mobile session + evidence harness.
2. Shell v2: window mode controls (fullscreen/minimize/restore,
   orientation handling), window switcher via wmctrl/xdotool on the
   jwm session, separate Android/Linux Home pages with adjustable
   layout. NEW FILES in my lane only.
3. Glass pass: picom config (dual_kawase blur, corner-radius, opacity
   rules) for the mobile session + effects toggle + perf evidence.
4. Input-layer verdict (app worker lane): fix-or-rebuild decision on
   gesture emulation per the 19:07 conditional authority.
5. Motion/compositor decision (only if glass+layout prove out and Ash
   still wants iOS-style transitions): evaluate compositor options
   with full supply-chain and perf review.

Explicitly NOT in this plan: per-app adaptation testing (Ash waived,
he tests), any X server rewrite, any capability reduction, any claim
of smooth performance before measurement.
