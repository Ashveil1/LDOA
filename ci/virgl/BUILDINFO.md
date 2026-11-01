# Build provenance for the committed virgl_test_server artifacts

- Built by ci/virgl/build-virgl.sh on 2026-11-01 (this branch, commit-local).
- NDK: android-ndk-r29, Pkg.Revision 29.0.14206865 (same as CI pins in
  .github/workflows/input-harness.yml), meson 1.12.1 + ninja 1.13.2.
- Inputs (SHA-256 verified by the script at build time):
  virglrenderer 1.3.0 (MIT) 56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce
  libepoxy 1.5.10 (MIT) a7ced37f4102b745ac86d6a70a9da399cc139ff168ba6b8002b4d8d43c900c15
- Flags: libepoxy -Ddefault_library=static -Degl=yes -Dglx=no -Dx11=false;
  virglrenderer -Ddefault_library=static -Dplatforms=egl -Dvenus=false
  -Dtests=false; own NDK compat shims for <log/log.h> +
  <cutils/properties.h> (generated inline by the script).
- Output hashes: see ARTIFACTS.sha256 (regenerate after any rebuild).
