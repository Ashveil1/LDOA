# VirGL-first GPU spike (bounded experiment, branch gnome/virgl-spike)

Bounded opt-in experiment (approved 2026-11-01, owner authority kept
internal - never in this repo): Android host GPU + Ubuntu guest
drawing through virgl; GNOME/user-data/rootless preserved; no fps promises;
emulator results are NOT Adreno results; no phone testing access.

## Contract (all facts verified from source or binaries, 2026-11-01)

Host (Android app process):
- virgl_test_server from virglrenderer 1.3.0 (MIT), built EGL-only
  (-Dplatforms=egl, -Dvenus=false - GL-only bounded spike) with static
  libepoxy 1.5.10 (MIT, -Degl=yes -Dglx=no -Dx11=false). It renders via the
  SYSTEM libEGL/libGLESv2 = the phone's real GLES driver (Adreno on the
  POCO M7 4G). Launch shape: virgl_test_server --use-egl-surfaceless
  --use-gles --socket-path <path> (flags verified in vtest/vtest_server.c
  of the pinned 1.3.0 tarball, SHA-256
  56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce).

Guest (Ubuntu noble, stock mesa-libgallium 25.2.8):
- virpipe driver is already in the stock package (binary strings evidence).
- Connects to the vtest unix socket: env VTEST_SOCKET_NAME overrides the
  default /tmp/.virgl_test (env var + default string both verified in the
  exact guest .deb's libgallium.so AND in mesa-25.2.8
  src/gallium/winsys/virgl/vtest/virgl_vtest_socket.c:203-212).
- The launcher script binds the host socket into the proot guest and sets
  GALLIUM_DRIVER=virpipe only for opt-in debug launches.

Build: ci/virgl/build-virgl.sh (own glue; pins + SHA-256 both sources;
NDK, meson, ninja required). Artifacts are committed prebuilt under
app/src/main/jniLibs/<abi>/libvirgl_test_server.so, matching the repo's
existing prebuilt-native pattern (proot).
