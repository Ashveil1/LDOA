#!/bin/sh
# Build the VirGL host render server for LDOA's VirGL-first GPU spike.
#
# Own glue code (no third-party code copied into this repo): downloads and
# builds from pinned upstream sources, verified by SHA-256:
#   - virglrenderer 1.3.0 (MIT, gitlab.freedesktop.org/virgl/virglrenderer)
#   - libepoxy 1.5.10     (MIT, github.com/anholt/libepoxy)
# Build-shape facts (NDK clang, meson cross files, EGL-only libepoxy,
# -Dplatforms=egl) are consistent with the public termux-packages
# virglrenderer-android recipe (MIT), used as evidence only.
#
# Output per ABI: libvirgl_test_server.so - the vtest render server as ONE
# statically-linked executable (libepoxy + virglrenderer both static), named
# .so so jniLibs extraction ships it to nativeLibraryDir, the verified exec
# path at targetSdk 34 (same pattern as libproot.so).
#
# Requires: sh, curl, tar, sha256sum, python3, meson, ninja, pkg-config and
# an Android NDK (ANDROID_NDK_HOME or $ANDROID_HOME/ndk/<latest>).
set -eu

VR_VERSION=1.3.0
VR_SHA256=56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce
EPOXY_VERSION=1.5.10
EPOXY_SHA256=a7ced37f4102b745ac86d6a70a9da399cc139ff168ba6b8002b4d8d43c900c15
API_LEVEL=26   # app minSdk

OUT_DIR=${1:-app/src/main/jniLibs}
[ -d "$OUT_DIR" ] || { echo "ERROR: jniLibs dir not found: $OUT_DIR (run from repo root)"; exit 1; }

NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}
if [ -z "$NDK" ] && [ -n "${ANDROID_HOME:-}" ]; then
  NDK=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)
fi
[ -n "$NDK" ] && [ -x "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android$API_LEVEL-clang" ] \
  || { echo "ERROR: Android NDK not found (set ANDROID_NDK_HOME)"; exit 1; }
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin

for t in meson ninja pkg-config patch curl sha256sum tar python3; do
  command -v "$t" >/dev/null 2>&1 || { echo "ERROR: missing tool: $t"; exit 1; }
done

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

# Own compatibility shims (no third-party code): virglrenderer's vendored
# mesa util/os_misc.c includes <log/log.h> and <cutils/properties.h>, which
# are Android-platform (libcutils) headers the NDK no longer ships. Map them
# onto the public NDK APIs (<android/log.h>, <sys/system_properties.h>).
mkdir -p "$WORK/compat-include/log" "$WORK/compat-include/cutils"
cat > "$WORK/compat-include/log/log.h" <<'HEOF'
#ifndef LDOA_COMPAT_LOG_LOG_H
#define LDOA_COMPAT_LOG_LOG_H
#include <android/log.h>
#ifndef LOG_PRI
#define LOG_PRI(priority, tag, ...) __android_log_print(priority, tag, __VA_ARGS__)
#endif
#endif
HEOF
cat > "$WORK/compat-include/cutils/properties.h" <<'HEOF'
#ifndef LDOA_COMPAT_CUTILS_PROPERTIES_H
#define LDOA_COMPAT_CUTILS_PROPERTIES_H
#include <string.h>
#include <sys/system_properties.h>
/* libcutils names -> NDK <sys/system_properties.h> names */
#ifndef PROPERTY_KEY_MAX
#define PROPERTY_KEY_MAX PROP_NAME_MAX
#endif
#ifndef PROPERTY_VALUE_MAX
#define PROPERTY_VALUE_MAX PROP_VALUE_MAX
#endif
static int property_get(const char *key, char *value, const char *default_value) {
    int n = __system_property_get(key, value);
    if (n == 0 && default_value != NULL) {
        size_t dl = strlen(default_value);
        memcpy(value, default_value, dl + 1);
        return (int)dl;
    }
    return n;
}
#endif
HEOF
COMPAT_INC="-I$WORK/compat-include"

echo "== download pinned sources"
curl -sSL -o vr.tar.gz "https://gitlab.freedesktop.org/virgl/virglrenderer/-/archive/virglrenderer-$VR_VERSION/virglrenderer-virglrenderer-$VR_VERSION.tar.gz"
echo "$VR_SHA256  vr.tar.gz" | sha256sum -c -
tar xzf vr.tar.gz

# Patches (ci/virgl/patches/, applied in order):
#   0001-do-not-use-gbm.patch - termux-packages virglrenderer-android
#     (MIT, packages/virglrenderer-android @ termux c772c595b2, same
#     1.3.0 pin + SHA-256): with_host_android enables have_egl without
#     gbm/libdrm (absent on the NDK) and routes vrend_winsys_init's EGL
#     branch to the Android virgl_egl_init(EGLNativeDisplayType) variant
#     under __ANDROID__. WITHOUT it have_egl=false and the EGL winsys is
#     compiled OUT - the binary can only fail with "EGL is not supported
#     on this platform" (run 41316351369).
#   0002-egl-pbuffer-fallback.patch - own (same MIT terms as upstream):
#     capability-checked 1x1 pbuffer surface when the display lacks
#     EGL_KHR_surfaceless_context (Android emulator SwiftShader case,
#     measured by ci/virgl/hostprobe.c); EGL_KHR_create_context stays
#     required; non-surfaceless callers are unchanged.
#   0003-egl-pbuffer-fail-cleanup.patch - own (same MIT terms): destroy
#     the context and terminate the display when the fallback pbuffer
#     creation fails, instead of leaking both on the error path.
#   0004-egl-unbind-no-surface.patch - own (same MIT terms): an unbind
#     (EGL_NO_CONTEXT) must pair with EGL_NO_SURFACE for draw/read -
#     the EGL 1.4 spec fails surface+EGL_NO_CONTEXT with EGL_BAD_MATCH
#     (the vrend sync thread unbinds this way at exit,
#     vrend_renderer.c:7398; observed in CI run 41320469908). Real
#     context binds still use the pbuffer; surfaceless-capable builds
#     are unchanged.
for p in "$OLDPWD/ci/virgl/patches/"*.patch; do
  echo "== applying $(basename "$p")"
  patch -d "virglrenderer-virglrenderer-$VR_VERSION" -p1 < "$p"
done
curl -sSL -o epoxy.tar.gz "https://github.com/anholt/libepoxy/archive/refs/tags/$EPOXY_VERSION.tar.gz"
echo "$EPOXY_SHA256  epoxy.tar.gz" | sha256sum -c -
tar xzf epoxy.tar.gz

for ABI in arm64-v8a x86_64; do
  case $ABI in
    arm64-v8a) TRIPLE=aarch64-linux-android; CPUFAM=aarch64; CPU=armv8-a ;;
    x86_64)    TRIPLE=x86_64-linux-android;  CPUFAM=x86_64;  CPU=x86-64 ;;
  esac
  PREFIX=$WORK/prefix-$ABI
  PC=$WORK/pkg-config-$ABI
  printf '#!/bin/sh\nexport PKG_CONFIG_DIR=\nexport PKG_CONFIG_LIBDIR=%s/lib/pkgconfig\nexec pkg-config "$@"\n' "$PREFIX" > "$PC"
  chmod +x "$PC"

  CROSS=$WORK/cross-$ABI.ini
  cat > "$CROSS" <<XEOF
[binaries]
c = '$TC/$TRIPLE$API_LEVEL-clang'
cpp = '$TC/$TRIPLE$API_LEVEL-clang++'
ar = '$TC/llvm-ar'
strip = '$TC/llvm-strip'
pkg-config = '$PC'

[host_machine]
system = 'android'
cpu_family = '$CPUFAM'
cpu = '$CPU'
endian = 'little'

[built-in options]
c_args = ['-fPIC', '$COMPAT_INC']
cpp_args = ['-fPIC', '$COMPAT_INC']
c_link_args = ['-llog']
cpp_link_args = ['-llog']
XEOF

  echo "== $ABI: libepoxy (static, EGL-only)"
  meson setup "$WORK/epoxy-build-$ABI" "libepoxy-$EPOXY_VERSION" \
    --cross-file "$CROSS" --prefix="$PREFIX" --libdir=lib \
    -Ddefault_library=static -Degl=yes -Dglx=no -Dx11=false -Dtests=false
  ninja -C "$WORK/epoxy-build-$ABI" install

  echo "== $ABI: virglrenderer (static into the server, EGL platform, GL-only)"
  # venus=false: bounded GL spike, no Vulkan loader/header dependency.
  meson setup "$WORK/vr-build-$ABI" "virglrenderer-virglrenderer-$VR_VERSION" \
    --cross-file "$CROSS" --prefix="$PREFIX" --libdir=lib \
    -Ddefault_library=static -Dplatforms=egl -Dvenus=false -Dtests=false
  ninja -C "$WORK/vr-build-$ABI" install

  SERVER=$(find "$PREFIX/bin" -name 'virgl_test_server*' -type f | head -1)
  [ -n "$SERVER" ] || { echo "ERROR: virgl_test_server not produced for $ABI"; exit 1; }
  mkdir -p "$OLDPWD/$OUT_DIR/$ABI"
  cp "$SERVER" "$OLDPWD/$OUT_DIR/$ABI/libvirgl_test_server.so"
  "$TC/llvm-strip" "$OLDPWD/$OUT_DIR/$ABI/libvirgl_test_server.so" || true
  echo "== $ABI: -> $OUT_DIR/$ABI/libvirgl_test_server.so"
done

echo "DONE. Verify: file + llvm-readelf -d on each output (no non-system NEEDED entries expected)."
