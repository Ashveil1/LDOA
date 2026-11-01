# xmap-pixels build / ABI / provenance

- Source: xmap-pixels.c, original test-only code written for this repo
  (no copied third-party code). Never shipped in a product artifact.
- Runtime build (CI runner ubuntu-24.04 ONLY):
    apt-get install -y libx11-dev
    gcc -O2 -Wall -Wextra -o xmap-pixels xmap-pixels.c -lX11
  Runner is ubuntu-24.04 (noble, glibc 2.39); the guest rootfs is
  Ubuntu noble (glibc 2.39) - same ABI generation, dynamic link
  against libX11.so.6. Guest runtime dep VERIFIED: noble x11-utils
  (already in the guest package list) depends on libx11-6, so
  libX11.so.6 is present in the rootfs. NOT host-gcc-portable in
  general: never build on a non-noble host for the guest.
- Host selftest (any host, no X11 needed):
    gcc -DXMAP_SELFTEST -O2 -Wall -Wextra -o xmap-pixels-selftest xmap-pixels.c
  Validates mask conversion, synthetic ZPixmap layouts (16/32bpp,
  LSB/MSBFirst), malformed-mask rejection, PPM assembly inputs.
  Host-fixture results say nothing about runtime X behavior; the CI
  compile of the runtime binary is the first real runtime-branch check.
- Deployment: test writes the binary to a launch-unique guest /tmp path
  (ldoa_put_managed manifest+sha256 pattern), finally-cleanup; the
  helper creates no windows and issues only read-only requests
  (GetWindowAttributes/GetImage/XSync).

## ABI evidence chain (records, 2026-11-02)
- Sandbox here is Ubuntu 22.04 (jammy): local validation compiled and
  linked against JAMMY libx11-dev 2:1.7.5-1ubuntu0.4 (headers +
  libX11.so.6.4.0, SONAME libX11.so.6). The resulting binary's NEEDED
  records are exactly libX11.so.6 + libc.so.6.
- Noble (guest rootfs AND ubuntu-24.04 CI runner): libx11-6 =
  2:1.8.7-1ubuntu0.1 (packages.ubuntu.com/noble/libx11-6), same SONAME
  libX11.so.6; libc6 = 2.39-0ubuntu8.x (packages.ubuntu.com/noble/libc6).
  x11-utils (noble) Depends: libx11-6 (packages.ubuntu.com/noble/x11-utils,
  "dep: libx11-6").
- The local jammy build validates code + linkage only; the binary that
  runs in the guest MUST be built by the CI step on the noble runner
  (gcc + libx11-dev), never copied from a host build.
- Guest-side loader evidence (labeled inference): the session run's
  provisioning apt-install completed and xwininfo (x11-utils) executed
  in the guest census; apt hard-dependency resolution therefore had
  libx11-6 installed. Direct in-guest loader proof is recorded by the
  run itself: the caller script logs `ldconfig -p | grep libX11`
  (XP_LDCONFIG line) before invoking the helper, so the runtime
  loader record is evidence, not inference.
