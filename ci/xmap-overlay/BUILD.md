# xmap-overlay build / ABI / provenance

- Source: xmap-overlay.c, original test-only code written for this repo
  (no copied third-party code). Never shipped in a product artifact.
- Runtime build (CI runner ubuntu-24.04 ONLY):
    apt-get install -y libx11-dev libxcomposite-dev
    gcc -O2 -Wall -Wextra -o xmap-overlay xmap-overlay.c -lX11 -lXcomposite
  Runner is ubuntu-24.04 (noble, glibc 2.39); the guest rootfs is
  Ubuntu noble (glibc 2.39) - same ABI generation. NOT host-gcc-portable
  in general: never build on a non-noble host for the guest.
- Guest runtime deps: libX11.so.6 (as ci/xmap-pixels/BUILD.md) and
  libXcomposite.so.1 (noble libxcomposite1). Presence in the GNOME
  rootfs is a labeled INFERENCE from mutter linking libXcomposite
  (XCompositeGetOverlayWindow) - and is checked AT RUNTIME: the caller
  logs ldconfig lines (XO_LDCONFIG) and the wrapper exits 10
  (XO_SKIPPED reason=guest-libs-absent, a recorded outcome, never a
  failure) when the SONAME is absent; the helper then never runs and
  root-only evidence stands.
- Host selftest (any host, no X11 needed):
    gcc -DXMAP_SELFTEST -O2 -Wall -Wextra -o xmap-overlay-selftest xmap-overlay.c
  Scenario doubles drive the REAL run_probe control flow: owner-None
  skip (asserts ZERO GetOverlayWindow calls), CM-atom-absent skip,
  owner-lost / owner-changed selection races, GetOverlayWindow X-error
  with and without a returned XID (uncertain acquisition: no release
  issuable, disconnect backstop), partial sample, write failure,
  release X-error, Composite extension absent, all-patches-fail.
  Host-fixture results say nothing about runtime X behavior.
- Deployment: the test writes the binary to a launch-unique guest /tmp
  path (writeBytes + recorded sha256 pair, reviewer comparison not a
  gate), finally-cleanup.
- SIDE EFFECTS (by design, recorded, never claimed passive):
  XCompositeGetOverlayWindow is an acquisition+mapping operation issued
  ONLY when the _NET_WM_CM_S0 selection has a non-None owner (a None
  owner means NO request - the call itself would create/map the
  overlay); the owner is re-read immediately before and after; matched
  XCompositeReleaseOverlayWindow + XCloseDisplay on every path after a
  successful Get; post-get mapstate is never claimed as preexisting
  viewability; release is never claimed to restore pre-probe state.

## ABI evidence chain (records, 2026-11-02)
- Sandbox here is Ubuntu 22.04 (jammy): local validation compiled and
  LINKED against REAL jammy headers/libs (apt-get download libx11-dev
  2:1.7.5 + libxcomposite-dev 1:0.4.5, dpkg-deb -x, -I/-L against the
  extracted tree; zero warnings with -Wall -Wextra). The resulting
  binary's NEEDED records are exactly libX11.so.6 + libXcomposite.so.1
  + libc.so.6.
- Real-server smoke (local Xvfb, jammy libs): (A) no compositor ->
  rc 10 skipped=no-cm-atom, zero overlay requests, no artifact (this
  run caught and fixed the atom-absent path, previously misreported as
  an X error); (B) a client holding _NET_WM_CM_S0 -> full Tier-2 path,
  5/5 patches, XO_RELEASE attempted=1 result=confirmed, artifact
  written. These validate code + linkage + protocol flow on THIS jammy
  server only; the binary that runs in the guest MUST be built by the
  CI step on the noble runner, never copied from a host build.
- Noble package records: libxcomposite1 (SONAME libXcomposite.so.1),
  libx11-6 (SONAME libX11.so.6) - packages.ubuntu.com/noble; same
  SONAME discipline as ci/xmap-pixels/BUILD.md.
- Producer attribution is UNKNOWN by design: XC-MISC GetXIDRange
  returns an available ID allocation range, NOT the connection's
  resource-id mask, so it cannot establish a client partition; no XID
  high-bit partition is ever assumed, and no optional extension is
  pulled in for attribution.
