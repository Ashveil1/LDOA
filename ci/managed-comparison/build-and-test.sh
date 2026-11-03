#!/bin/bash
# LOCAL UNEXECUTED recipe proposal. Build only, no binary publication/install.
# Caller must authorize compilation separately. Outputs stay disposable runner-local.
set -euo pipefail
SCRIPT=$(realpath -e "$0")
ROOT=$(cd "$(dirname "$SCRIPT")/../.." && pwd)
: "${FLOOR_EVIDENCE:?existing output directory}"
: "${FIXED_PREFIX_REVIEWED_BUILD:?set explicitly only after separate build approval}"
[[ $FIXED_PREFIX_REVIEWED_BUILD == yes ]]
E=$(realpath -e "$FLOOR_EVIDENCE")
# Explicit nonbinary receipt outside private E; never export private log tails.
RECEIPT_DIR="$ROOT/comparison-evidence"
mkdir -p "$RECEIPT_DIR"
RECEIPT="$RECEIPT_DIR/host-phase.txt"
[[ ! -e "$RECEIPT" && ! -L "$RECEIPT" ]]
printf 'schema=1\n' > "$RECEIPT"
PHASE=host-check;CASE=none
trap 'rc=$?; printf "phase=%s case=%s rc=%s\n" "$PHASE" "$CASE" "$rc" >> "$RECEIPT"' EXIT
exec > >(tee "$E/experiment.log") 2>&1
. /etc/os-release
[[ $ID == ubuntu && $VERSION_ID == 24.04 && $(uname -m) == x86_64 ]]
W=$(mktemp -d)
trap 'rc=$?; printf "phase=%s case=%s rc=%s\n" "$PHASE" "$CASE" "$rc" >> "$RECEIPT"; rm -rf "$W"' EXIT
# Output holds runner-local source/stages, NOT artifact/log publication.
LOCAL=$(mktemp -d "$E/runner-local.XXXXXX")
chmod 0700 "$LOCAL"
cp "$SCRIPT" "$E/build-recipe.sh"
cp "$ROOT/ci/managed-comparison/default-size.patch" "$E/default-size.patch"
cp /etc/os-release "$E/os-release"
{ gcc --version; ld --version; make --version; pkg-config --version; } > "$E/toolchain.txt"
dpkg-query -W '-f=${Package}\t${Architecture}\t${Status}\t${Version}\n' '*' > "$E/build-packages.txt"
printf '%s\n' 'CC=gcc CFLAGS=-O2 -g0 CPPFLAGS= LDFLAGS= LC_ALL=C SOURCE_DATE_EPOCH=1714082400' > "$E/explicit-build-environment.txt"
export CC=gcc CFLAGS='-O2 -g0' CPPFLAGS= LDFLAGS= LC_ALL=C SOURCE_DATE_EPOCH=1714082400
cd "$W"
fetch() { curl --retry 0 --connect-timeout 10 --max-time 45 -fsSL "$1" -o "$2"; }
PHASE=source-fetch
fetch https://archive.ubuntu.com/ubuntu/dists/noble/InRelease InRelease
gpgv --keyring /usr/share/keyrings/ubuntu-archive-keyring.gpg InRelease 2> "$E/archive-signature.txt"
fetch https://archive.ubuntu.com/ubuntu/dists/noble/universe/source/Sources.xz Sources.xz
python3 - "$W" <<'PY'
import hashlib,lzma,pathlib,sys
p=pathlib.Path(sys.argv[1]);h=hashlib.sha256((p/'Sources.xz').read_bytes()).hexdigest()
assert any(h in l and l.endswith('universe/source/Sources.xz') for l in (p/'InRelease').read_text().splitlines())
s=lzma.decompress((p/'Sources.xz').read_bytes()).decode()
r=next(r for r in s.split('\n\n') if r.startswith('Package: pcmanfm\n'))
assert 'Version: 1.3.2-4build2\n' in r
(p/'source-record.txt').write_text(r)
for h in ['14cb7b247493c4cce65fbb5902611e3ad00a7a870fbc1e50adc50428c5140cf7','ac2538fe14f44bd56ea77f1bba8c4f16349898ae259e2b602465f4d2faec0723']:
 assert h in r
PY
fetch https://archive.ubuntu.com/ubuntu/pool/universe/p/pcmanfm/pcmanfm_1.3.2.orig.tar.xz orig.tar.xz
fetch https://archive.ubuntu.com/ubuntu/pool/universe/p/pcmanfm/pcmanfm_1.3.2-4build2.debian.tar.xz debian.tar.xz
printf '%s\n' '14cb7b247493c4cce65fbb5902611e3ad00a7a870fbc1e50adc50428c5140cf7  orig.tar.xz' 'ac2538fe14f44bd56ea77f1bba8c4f16349898ae259e2b602465f4d2faec0723  debian.tar.xz' | sha256sum -c -
printf '%s  %s\n' ae69d35d1215efc6e521c7fbe81ceacb261fd0c4917c2828ffb385bded7d6829 "$E/default-size.patch" | sha256sum -c -
cp InRelease source-record.txt orig.tar.xz debian.tar.xz "$E/"
# SAME fixed configure prefix and flags. Separate case staging only;
# no host-prefix installation, symlink swap, or binary execution.
PREFIX="/opt/ldoa/pcmanfm/1.3.2-4build2-floor1"
[[ ! -e "$PREFIX" && ! -L "$PREFIX" ]] # do not touch hostprefix
cp orig.tar.xz debian.tar.xz source-record.txt InRelease "$LOCAL/"
cp "$E/default-size.patch" "$LOCAL/"
printf '%s\n' 'Upstream1.3.2 with Debian1.3.2-4build2 metadata EXTRACTED only; Debian quilt patches NOT applied. Not official Ubuntu binary.' > "$LOCAL/SOURCE-LABEL.txt"
for case in original patched; do
 CASE=$case;PHASE=extract
 mkdir "$W/$case"
 tar -xf orig.tar.xz -C "$W/$case"
 cd "$W/$case/pcmanfm-1.3.2"
 tar -xf "$W/debian.tar.xz"
 cp COPYING debian/copyright "$E/"
 if [[ $case == patched ]]; then patch -p1 < "$E/default-size.patch"; fi
 cp src/desktop.c "$E/desktop-$case.c"
 printf '%s\n' "./configure --with-gtk=3 --prefix=$PREFIX" 'make -j2' "make DESTDIR=$W/$case/stage install" > "$E/commands-$case.txt"
 PHASE=configure
 ./configure --with-gtk=3 --prefix="$PREFIX" > "$E/configure-$case.log" 2>&1
 PHASE=build
 make -j2 > "$E/build-$case.log" 2>&1
 PHASE=stage
 make DESTDIR="$W/$case/stage" install > "$E/stage-$case.log" 2>&1
 PHASE=elf-inspect
 file "$W/$case/stage$PREFIX/bin/pcmanfm" > "$E/file-$case.txt"
 readelf -h -l -d --version-info "$W/$case/stage$PREFIX/bin/pcmanfm" > "$E/abi-$case.txt"
 sha256sum "$W/$case/stage$PREFIX/bin/pcmanfm" > "$E/binary-$case.sha256"
 cd "$W"
done
PHASE=resource-equality;CASE=both
python3 - "$W" "$PREFIX" <<'PY'
import pathlib,hashlib,sys,json
w,p=sys.argv[1:];man=[];dirs=[]
for c in ['original','patched']:
 root=pathlib.Path(w,c,'stage'+p)
 dirs.append(sorted(str(f.relative_to(root)) for f in root.rglob('*') if f.is_dir()))
 man.append({str(f.relative_to(root)):hashlib.sha256(f.read_bytes()).hexdigest() for f in root.rglob('*') if f.is_file() and str(f.relative_to(root))!='bin/pcmanfm'})
assert man[0]==man[1], 'resource-prefix confound'
assert dirs[0]==dirs[1], 'directory-set confound'
pathlib.Path(w,'resource-manifest.json').write_text(json.dumps(man[0],indent=2))
PY
cp "$W/resource-manifest.json" "$E/"
for case in original patched; do
 CASE=$case;PHASE=source-inventory
 # Fixedprefix remains absent on HOST. Stage remains separate case dir;
 # do NOT install to /usr or symlink host /opt. Neither binary executed.
 staged="$W/$case/stage$PREFIX"
 cp "$W/$case/pcmanfm-1.3.2/COPYING" "$staged/COPYING"
 cp "$W/$case/pcmanfm-1.3.2/debian/copyright" "$staged/DEBIAN-COPYRIGHT"
 printf '%s\n' 'Corresponding source in runner-local source bundle, original tar+Debian metadata+exact applied floorpatch+recipe+flags. No public binary offered.' > "$staged/SOURCE.txt"
 cp -a "$W/$case" "$LOCAL/$case"
 # Complete configured/built source tree is retained runner-local withstage;
 # no executable archive/log chunk or license-incomplete binary publication.
 find "$staged" -type l -print > "$E/symlinks-$case.txt"
 (cd "$staged"; find . -type f -printf '%P\0' | sort -z | xargs -0 sha256sum) > "$E/inventory-$case.sha256"
 cp "$E/inventory-$case.sha256" "$LOCAL/$case/stage$PREFIX/inventory.sha256"
 (cd "$staged"; find . -type d -print | LC_ALL=C sort) > "$E/directories-$case.txt"
 [[ -d "$staged/lib/pcmanfm" && -f "$staged/share/pcmanfm/ui/desktop-pref.ui" ]]
done
PHASE=recipe-copy;CASE=both
cp "$SCRIPT" "$LOCAL/build-recipe.sh"
printf '%s\n' "$LOCAL" > "$E/runner-local-location.txt"
PHASE=complete
echo 'BUILD ONLY; staged source/binaries runner-local, no guest install/execution/publication. Separate reviewed guest ABI/staging gate required.'
