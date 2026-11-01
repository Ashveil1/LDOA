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
- Modifications to vendored sources: exactly one compatibility line, applied
  at configuration time by settings.gradle.kts (a dirty submodule edit would
  not survive a clean recursive checkout, so the parent repo owns it) -
  (a) lorie/build.gradle host-app detection uses `it.dependencyProject.path`
  instead of `it.path` (ProjectDependency.getPath exists only in Gradle 9;
  this repo builds with Gradle 8.9 / AGP 8.7.3); (b) vendored
  compileSdkVersion 34 -> 35 (their current androidx set requires 35; the
  androidx.lifecycle group is separately pinned to 2.8.7 in the root
  build.gradle.kts because 2.11.0 demands AGP 9.1 / compileSdk 37);
  (c) lorie/.../CmdEntryPoint.java initEntryPoint preloads libXlorie.so from
  the XSERVER_LIB env var when set - the upstream classloader-resource load
  only works with extractNativeLibs=false, and this app must keep
  extractNativeLibs=true (CI-verified: filesDir exec is blocked at
  targetSdk 34, error=13; nativeLibraryDir execution is the verified path).

## xkeyboard-config (runtime payload, downloaded on-device)
- Source: https://dl-cdn.alpinelinux.org/alpine/v3.21/main/x86_64/xkeyboard-config-2.43-r0.apk
- Pinned: version 2.43-r0, SHA-256 cab4611f7b37fecd2fc9e160585f6fb6c8e9f0fab2da3df6c516649247406db7, verified at provision time
- Purpose: XKB_CONFIG_ROOT keymap data required by the embedded X server
- License: MIT (per Alpine APKINDEX)
  Integration-only changes in this repo: settings.gradle.kts module mapping,
  app manifest strips the library's LAUNCHER intent-filter (single launcher
  icon), CI installs NDK 29.0.14206865 (required by lorie/version.gradle)
  and bison.

## proot (binary, install/runtime phase)
- Source: https://github.com/termux/proot (Termux apt build 5.1.107.101)
- Shipped as prebuilt shared libraries under app/src/main/jniLibs (arm64-v8a,
  x86_64), patchelf-adjusted libtalloc dependency
- License: GPL-2.0

## Alpine Linux minirootfs (runtime payload, downloaded on-device)
- Source: https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/{aarch64,x86_64}/
- Pinned: Alpine 3.21.3 minirootfs, SHA-256 verified per ABI at install time

## virglrenderer (binary, VirGL GPU spike - experiment branch)
- Source: https://gitlab.freedesktop.org/virgl/virglrenderer
- Pinned: 1.3.0, SHA-256 56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce (tarball verified at build time by ci/virgl/build-virgl.sh)
- License: MIT (COPYING in the verified tarball; notice retained here and in the built artifact directory)
- Built for Android with NDK clang, EGL-only (-Dplatforms=egl -Dvenus=false), static libepoxy.

## virglrenderer Android build patch (termux-packages)
- Source: https://github.com/termux/termux-packages/tree/master/packages/virglrenderer-android
- Pinned: commit c772c595b2 (packages/virglrenderer-android), 0001-do-not-use-gbm.patch only
- License: MIT (termux-packages is MIT; the patch carries its own copyright header)
- Location: ci/virgl/patches/0001-do-not-use-gbm.patch (applied at build time by ci/virgl/build-virgl.sh)
- Purpose: enables virglrenderer's EGL winsys on Android without GBM/libdrm.

## libepoxy (statically linked into the virgl_test_server artifact)
- Source: https://github.com/anholt/libepoxy
- Pinned: 1.5.10, SHA-256 a7ced37f4102b745ac86d6a70a9da399cc139ff168ba6b8002b4d8d43c900c15
- License: MIT (COPYING in the verified tarball)
