# Phone Linux (prototype)

One Android app: pick a Linux distro -> install -> use a graphical desktop.
No commands required. Touch-first, fits phone screens, dark-first design.

Status: **prototype spike** (pre-release). This README is the working design record.

## Architecture (spike phase)

- **Own app, no Termux/UserLAnd dependency.** Kotlin + Jetpack Compose shell.
- **proot** (GPLv2, Termux-built binaries from the official Termux apt repo,
  currently 5.1.107.101) ships inside the APK under `jniLibs/<abi>/` renamed to
  `lib*.so` so Android extracts it to `nativeLibraryDir` (executable even with
  targetSdk 34; executing from the writable app-data dir is blocked by W^X
  since Android 10 - termux-app issue #1072).
- **Distro userlands** are pinned, SHA-256-verified rootfs tarballs
  (Alpine 3.21.3 minirootfs first). Downloaded with HTTP resume, verified,
  extracted into app-private storage. A derivative tuned userland
  (base + desktop + our config) replaces stock later; it is a profile,
  not a new distro - the kernel is Android's and cannot be replaced unrooted.
- **Desktop display** (next phase, after the spike): X server embedded in the
  app, derived from Termux:X11 (GPLv3). Own signature avoids the cross-app
  sharedUid workaround Termux:X11 documents for CPU deprioritization.

ABI strategy: both `arm64-v8a` (devices) and `x86_64` (emulator CI) proot
binaries + matching Alpine rootfs are bundled, selected by
`Build.SUPPORTED_ABIS[0]`. Emulator CI therefore tests the real flow with
native x86_64 components, no ARM translation assumed.

## What the spike proves (CI: .github/workflows/spike.yml)

1. App builds (assembleDebug) - first compiler check of all sources.
2. On an API 34 x86_64 emulator: distro install flow (download, checksum,
   extract incl. symlinks), `proot --version` exit 0, and **guest exec**:
   `/bin/sh` inside the Alpine rootfs runs and reports the expected arch.
   This retires the biggest packaging risk (guest exec under modern targetSdk).
3. UI flow: picker card + Install button render and are tappable.

Explicit evidence boundaries:
- x86_64 emulator proves exec/W^X semantics and flow, not ARM device behavior,
  touch feel, or speed. ARM-translation-based emulator runs are NOT assumed to
  work (ptrace under translation is unverified) and are not used.
- Device-level smoothness claims wait for real-hardware measurements.

## Licenses (re-verify at pinned versions before release)

- proot: GPLv2 (proot-me/proot). Shipped as unmodified binary + our patchelf
  dependency rename (build step documented, not source modification).
- proot loader: part of proot (GPLv2).
- libtalloc: LGPLv3+ (Samba). libandroid-shmem: BSD-style (Termux).
- Termux:X11 (next phase): GPLv3 -> app intended GPLv3 open source.
- This app: GPLv3 (owner-approved; LICENSE file added at repo creation).

## Security notes

- HTTPS-only downloads, SHA-256 pinned per ABI, resume via Range with
  Content-Range validation, 416 -> clean retry.
- Tar extraction guards: path traversal, relative symlink/hardlink escapes;
  absolute symlink targets allowed (proot remaps / into the rootfs).
- Failed extract never leaves a half-installed rootfs.
