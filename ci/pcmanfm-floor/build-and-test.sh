#!/bin/bash
# Dispatch-only experiment in a PUBLIC repository; artifacts public. Not product packaging.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${FLOOR_EVIDENCE:?existing output directory}"
E=$(realpath -e "$FLOOR_EVIDENCE")
exec > >(tee "$E/experiment.log") 2>&1
. /etc/os-release
[[ $ID == ubuntu && $VERSION_ID == 24.04 && $(uname -m) == x86_64 ]]
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
cp "$0" "$E/build-recipe.sh"
cp "$ROOT/ci/pcmanfm-floor/geometry.py" "$E/geometry.py"
cp "$ROOT/ci/pcmanfm-floor/default-size.patch" "$E/default-size.patch"
cp /etc/os-release "$E/os-release"
{ gcc --version; ld --version; make --version; pkg-config --version; } > "$E/toolchain.txt"
dpkg-query -W '-f=${Package}\t${Architecture}\t${Status}\t${Version}\n' '*' > "$E/build-packages.txt"
printf '%s\n' 'CC=gcc CFLAGS=-O2 -g0 CPPFLAGS= LDFLAGS= LC_ALL=C SOURCE_DATE_EPOCH=1714082400' > "$E/explicit-build-environment.txt"
export CC=gcc CFLAGS='-O2 -g0' CPPFLAGS= LDFLAGS= LC_ALL=C SOURCE_DATE_EPOCH=1714082400
cd "$W"
fetch() { curl --retry 0 --connect-timeout 10 --max-time 45 -fsSL "$1" -o "$2"; }
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
# SAME configure prefix and flags for both. Swap only a private symlink after
# case harness exits with no known group survivor/UNKNOWN. Escaped descendants
# are not proven absent. Resource files' hashes must match.
PREFIX="$W/private-prefix"
for case in original patched; do
 mkdir "$W/$case"
 tar -xf orig.tar.xz -C "$W/$case"
 cd "$W/$case/pcmanfm-1.3.2"
 tar -xf "$W/debian.tar.xz"
 cp COPYING debian/copyright "$E/"
 if [[ $case == patched ]]; then patch -p1 < "$E/default-size.patch"; fi
 cp src/desktop.c "$E/desktop-$case.c"
 printf '%s\n' "./configure --with-gtk=3 --prefix=$PREFIX" 'make -j2' "make DESTDIR=$W/$case/stage install" > "$E/commands-$case.txt"
 ./configure --with-gtk=3 --prefix="$PREFIX" > "$E/configure-$case.log" 2>&1
 make -j2 > "$E/build-$case.log" 2>&1
 make DESTDIR="$W/$case/stage" install > "$E/stage-$case.log" 2>&1
 file "$W/$case/stage$PREFIX/bin/pcmanfm" > "$E/file-$case.txt"
 readelf -h -d --version-info "$W/$case/stage$PREFIX/bin/pcmanfm" > "$E/abi-$case.txt"
 sha256sum "$W/$case/stage$PREFIX/bin/pcmanfm" > "$E/binary-$case.sha256"
 cd "$W"
done
python3 - "$W" "$PREFIX" <<'PY'
import pathlib,hashlib,sys,json
w,p=sys.argv[1:];man=[]
for c in ['original','patched']:
 root=pathlib.Path(w,c,'stage'+p)
 man.append({str(f.relative_to(root)):hashlib.sha256(f.read_bytes()).hexdigest() for f in root.rglob('*') if f.is_file() and str(f.relative_to(root))!='bin/pcmanfm'})
assert man[0]==man[1], 'resource-prefix confound'
pathlib.Path(w,'resource-manifest.json').write_text(json.dumps(man[0],indent=2))
PY
cp "$W/resource-manifest.json" "$E/"
for case in original patched; do
 ln -s "$W/$case/stage$PREFIX" "$PREFIX"
 python3 "$ROOT/ci/pcmanfm-floor/geometry.py" --binary "$PREFIX/bin/pcmanfm" --case "$case" --out "$E/$case"
 rm "$PREFIX"
done
# No patched executable archived, no binary installed to host /usr or product.
echo 'EXPERIMENT commands completed; actual PNG pixel review still required; no Android/FPS claim'
