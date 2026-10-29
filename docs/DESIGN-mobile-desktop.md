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
