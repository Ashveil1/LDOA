# Third-party components

## termux-x11 (vendored, desktop phase)
- Source: https://github.com/termux/termux-x11
- Pinned commit: 29d4460b26e69b39af023d255a255e2778251952 ("fix(present): clip GPU copies to window clipList", master HEAD 2026-10-25)
- Location: third_party/termux-x11 (git submodule, modules :lorie + :shell-loader:stub)
- License: GPL-3.0 (LICENSE inside the vendored tree). This app is distributed
  under GPLv3 accordingly; full source is published in this repository.
- The vendored tree pulls its own ~17 pinned C submodules (xorg xserver,
  pixman, libx11, libepoxy, xkbcomp, libtirpc, bzip2, ...); each carries its
  own license (predominantly MIT/X11-style) in its submodule directory.
- Modifications: none to vendored sources. Integration-only changes in this
  repo: settings.gradle.kts module mapping, app manifest strips the library's
  LAUNCHER intent-filter (single launcher icon), CI installs NDK 29.0.14206865
  (required by lorie/version.gradle) and bison.

## proot (binary, install/runtime phase)
- Source: https://github.com/termux/proot (Termux apt build 5.1.107.101)
- Shipped as prebuilt shared libraries under app/src/main/jniLibs (arm64-v8a,
  x86_64), patchelf-adjusted libtalloc dependency
- License: GPL-2.0

## Alpine Linux minirootfs (runtime payload, downloaded on-device)
- Source: https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/{aarch64,x86_64}/
- Pinned: Alpine 3.21.3 minirootfs, SHA-256 verified per ABI at install time
