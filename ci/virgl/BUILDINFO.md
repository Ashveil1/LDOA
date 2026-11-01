# Build provenance for the committed virgl_test_server artifacts

- Built by ci/virgl/build-virgl.sh on 2026-11-01 (this branch, commit-local).
- NDK: android-ndk-r29, Pkg.Revision 29.0.14206865 (same as CI pins in
  .github/workflows/input-harness.yml), meson 1.12.1 + ninja 1.13.2.
- Inputs (SHA-256 verified by the script at build time):
  virglrenderer 1.3.0 (MIT) 56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce
  libepoxy 1.5.10 (MIT) a7ced37f4102b745ac86d6a70a9da399cc139ff168ba6b8002b4d8d43c900c15
- Flags: libepoxy -Ddefault_library=static -Degl=yes -Dglx=no -Dx11=false;
  virglrenderer -Ddefault_library=static -Dvenus=false -Dtests=false;
  own NDK compat shims for <log/log.h> + <cutils/properties.h>
  (generated inline by the script).
- Source patches applied by the script (ci/virgl/patches/, in order):
  0001-do-not-use-gbm.patch - from termux-packages
  (packages/virglrenderer-android @ c772c595b2, MIT; same virglrenderer
  1.3.0 pin): meson with_host_android enables have_egl without GBM/libdrm;
  vrend_winsys.c gets the __ANDROID__ virgl_egl_init branch.
  0002-egl-pbuffer-fallback.patch - own: when EGL_KHR_surfaceless_context is
  missing in surfaceless mode (Android emulator), create a 1x1 pbuffer and
  make the context current on it instead of failing; extension gates
  unchanged otherwise.
- Rebuilt 2026-11-01 with the above patches; compile-level validation on the
  build tree: config.h has HAVE_EPOXY_EGL_H defined and ENABLE_GBM undefined;
  libvirglrenderer.a exports virgl_egl_init / virgl_egl_make_context_current;
  vrend_winsys.c.o calls virgl_egl_init (PLT32 relocation) and contains no
  "EGL is not supported" error branch; vrend_winsys_egl.c.o carries the
  eglCreatePbufferSurface/eglMakeCurrent relocations.
- Output hashes: see ARTIFACTS.sha256 (regenerate after any rebuild).
