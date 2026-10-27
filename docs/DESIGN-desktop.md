# Desktop phase design (plan - not built yet)

Goal: after Install, "Open desktop" shows a real XFCE session running in the
guest, rendered by an X server embedded in this app, touch-usable and
screen-fitted. No second app, no commands.

## Components

1. **X server: vendored termux-x11 at a pinned commit.**
   termux-x11 (GPLv3) is an Android app whose `lorie` module builds a full
   Xorg xserver from ~20 C submodules (xserver, pixman, libx11, libepoxy,
   xkbcomp, libtirpc, ... - see upstream .gitmodules). Embed = vendor the
   module (git submodule pinned by SHA, or subtree import with LICENSE and
   provenance file) and include it from our settings.gradle.kts.
   - License: embedding GPLv3 source => app distributed GPLv3 open source
     (owner already approved GPLv3). Keep all headers; add THIRD_PARTY_NOTICES.
   - Pin policy: exact commit SHA recorded; license re-verified at the pin
     before release.
   - Own signature: our app and the X server are one APK, so the cross-app
     sharedUid workaround upstream documents is unnecessary; Android keeps
     our process foreground-priority while the desktop activity is visible.
     (Background CPU limits still exist; covered by background/resume tests.)

2. **X connection, all inside our process:**
   X server on a unix socket in our private dir; proot launched with
   `-b <socketdir>:/tmp/.X11-unix` and `DISPLAY=:0` so guest apps connect
   directly. No TCP, no second app.

3. **Desktop userland: tuned Alpine + XFCE profile.**
   Post-install step runs `apk add` for xfce4, xfce4-terminal, dbus, font +
   our config (theme, panel layout, Xft.dpi from the device's real density)
   via a guest script, with the same progress/error UX as the distro
   download. Later option: pre-built fat rootfs image for one-download
   install (build in CI, pinned SHA-256). Session start:
   `dbus-launch --exit-with-session xfce4-session` under proot.

4. **Touch + screen fit.**
   termux-x11 touch modes (direct touch default, trackpad toggle) exposed as
   one control in our UI; native resolution, Xft.dpi and GTK scaling from
   DisplayMetrics; landscape-first layout with portrait fallback;
   on-screen keyboard via the Android IME (Compose overlay or termux-x11's
   own keyboard handling - pick during implementation).

## Acceptance evidence (before any device claims)

- Emulator CI journey extended: install -> open desktop -> XFCE panel visible
  (screenshot), launch xfce4-terminal via synthetic tap, type + run a command
  in the GUI terminal, portrait/landscape rotation, background -> foreground
  resume with session intact. Screenshots inspected per milestone.
- Measured: desktop cold-start latency and idle RSS recorded in CI logs;
  budgets set from measurements.
- Still not claimed: real-device smoothness, ARM64-specific behavior,
  arbitrary app compatibility.
