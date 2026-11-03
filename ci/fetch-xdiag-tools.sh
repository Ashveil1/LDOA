#!/bin/bash
# fetch-xdiag-tools.sh - fetch, verify and REGENERATE app/src/androidTest/assets/xdiag/
# Provenance chain (every link verified, fail closed):
#   pinned Ubuntu archive key fingerprint -> HKPS keyserver fetch, fingerprint
#   re-checked -> gpgv verifies noble InRelease -> InRelease carries sha256+size
#   of universe Packages.gz AND Sources.gz -> Packages.gz carries sha256+size of
#   the .deb -> deb extracts the pristine binary (sha256 pinned) -> the staged
#   asset is REPRODUCED from it by the documented byte patch and its sha256 must
#   equal the pinned asset hash. Sources.gz carries sha256 of the .dsc / orig
#   tarball / debian tarball (GPL corresponding source) -> bundled under source/.
# Anchoring note: the key is fetched over HKPS from Ubuntu's own keyserver and
# pinned by full fingerprint; operators wanting a pre-seeded root can drop the
# system /usr/share/keyrings/ubuntu-archive-keyring.gpg next to this script as
# ubuntu-archive-keyring.gpg and it will be used instead.
set -euo pipefail

# XDIAG_TEST_* overrides exist ONLY so ci/test_xdiag_fetch.py can run the
# signed-chain logic against a local fixture mirror. They are OPT-IN: unless
# XDIAG_FIXTURE_MODE=1 is set explicitly they are stripped, so ambient
# environment can never replace the production trust root or pins.
if [ "${XDIAG_FIXTURE_MODE:-0}" != "1" ]; then
  unset XDIAG_MIRROR XDIAG_TEST_KEY_FPR XDIAG_TEST_KEYRING \
        XDIAG_TEST_PRISTINE_SHA256 XDIAG_TEST_PATCHED_SHA256 XDIAG_TEST_PROTO_COUNT
fi

RELEASE=noble
ARCH=amd64
PKG=xtrace
# XDIAG_MIRROR/XDIAG_TEST_KEY_FPR exist ONLY so ci/test_xdiag_fetch.py can
# point the script at a local fixture mirror and fixture key; production
# runs never set them and get the pinned values below.
MIRROR=${XDIAG_MIRROR:-https://archive.ubuntu.com/ubuntu}
KEYSERVER=https://keyserver.ubuntu.com
# Ubuntu Archive Automatic Signing Key (2018) <ftpmaster@ubuntu.com> - full fingerprint:
ARCHIVE_KEY_FPR="${XDIAG_TEST_KEY_FPR:-F6ECB3762474EDA9D21B7022871920D1991BC93C}"
PRISTINE_BIN_SHA256="${XDIAG_TEST_PRISTINE_SHA256:-493e500d1c7502dfd00b1385d60cb5881be8d9681252ffee77e10356449fbf03}"
PATCHED_ASSET_SHA256="${XDIAG_TEST_PATCHED_SHA256:-2117568790d9ebd9983d61cc58de8374b3759f442e08148ce287a3411ddaf489}"
PROTO_COUNT="${XDIAG_TEST_PROTO_COUNT:-28}"

DEST="$(realpath -m "${1:-app/src/androidTest/assets/xdiag}")" # canonicalize BEFORE cd
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)" # before cd; shared helpers live next to this script
# DEST guard runs BEFORE any network or destructive work: only the
# canonical assets dir or a clearly-scoped throwaway /tmp/xdiag* path; a
# DEST that is an ancestor of this script/repo is always refused.
EXPECTED_DEST="$(realpath -m "$SCRIPT_DIR/../app/src/androidTest/assets/xdiag")"
case "$DEST" in
  "$EXPECTED_DEST") ;;
  /tmp/xdiag?*) ;;
  *) echo "FAIL: refusing to replace unexpected DEST '$DEST'"; exit 1 ;;
esac
case "$SCRIPT_DIR"/ in "$DEST"/*)
  echo "FAIL: DEST '$DEST' is an ancestor of the script/repo"; exit 1 ;;
esac
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

echo "== 1/7 archive signing key (pinned fingerprint) =="
if [ -n "${XDIAG_TEST_KEYRING:-}" ]; then cp "$XDIAG_TEST_KEYRING" kr.gpg
elif [ -f "$SCRIPT_DIR/ubuntu-archive-keyring.gpg" ]; then cp "$SCRIPT_DIR/ubuntu-archive-keyring.gpg" kr.gpg; else
  curl -fsSL "$KEYSERVER/pks/lookup?op=get&search=0x$(echo "$ARCHIVE_KEY_FPR" | tr 'A-F' 'a-f')" -o key.asc
  gpg --no-default-keyring --keyring "$PWD/kr.gpg" --import key.asc >/dev/null 2>&1
fi
GOT_FPR=$(gpg --no-default-keyring --keyring "$PWD/kr.gpg" --with-colons --list-keys 2>/dev/null | awk -F: '/^fpr:/{print $10; exit}')
[ "$GOT_FPR" = "$ARCHIVE_KEY_FPR" ] || { echo "FAIL: archive key fingerprint $GOT_FPR != pinned $ARCHIVE_KEY_FPR"; exit 1; }

echo "== 2/7 InRelease + gpgv =="
curl -fsSL "$MIRROR/dists/$RELEASE/InRelease" -o InRelease
gpgv --status-fd 3 --keyring "$PWD/kr.gpg" InRelease 3>gpgv.status 2>/dev/null || { echo "FAIL: InRelease signature invalid"; exit 1; }
grep -q "^\[GNUPG:\] VALIDSIG $ARCHIVE_KEY_FPR " gpgv.status || { echo "FAIL: InRelease signer is NOT the pinned archive key"; exit 1; }

meta_hash() { # $1 = exact path inside the release -> "sha256 size"
  awk -v p="$1" '/^ [0-9a-f]{64} /{h=$1; s=$2; path=$3; if (path==p) {print h, s; exit}}' InRelease
}
fetch_verified() { # $1 = repo-relative path, $2 = sha256, $3 = size, $4 = out
  curl -fsSL "$MIRROR/$1" -o "$4"
  echo "$2  $4" | sha256sum -c - >/dev/null || { echo "FAIL: sha256 mismatch on $1"; exit 1; }
  [ "$(stat -c%s "$4")" = "$3" ] || { echo "FAIL: size mismatch on $1"; exit 1; }
}

echo "== 3/7 Packages.gz (hash from signed InRelease) =="
read PGZ_H PGZ_S <<< "$(meta_hash universe/binary-$ARCH/Packages.gz)"
[ -n "${PGZ_H:-}" ] || { echo "FAIL: Packages.gz not in InRelease"; exit 1; }
fetch_verified "dists/$RELEASE/universe/binary-$ARCH/Packages.gz" "$PGZ_H" "$PGZ_S" Packages.gz

echo "== 4/7 deb (hash from verified Packages.gz) =="
zcat Packages.gz > Packages.txt
awk -v pkg="$PKG" '
  /^Package: /{p=($2==pkg)} p&&/^Version: /{v=$2} p&&/^Filename: /{f=$2} p&&/^Size: /{s=$2}
  p&&/^SHA256: /{h=$2} p&&/^$/{if(f!=""){print f, s, h, v; exit}}' Packages.txt > deb.meta
read DEB_FILE DEB_SIZE DEB_SHA256 DEB_VER < deb.meta
[ -n "${DEB_FILE:-}" ] || { echo "FAIL: $PKG stanza not found"; exit 1; }
fetch_verified "$DEB_FILE" "$DEB_SHA256" "$DEB_SIZE" pkg.deb

echo "== 5/7 extract + pristine binary/proto/license verification =="
mkdir x && cd x && ar x ../pkg.deb && tar xf data.tar.* && cd ..
BIN=x/usr/bin/xtrace
echo "$PRISTINE_BIN_SHA256  $BIN" | sha256sum -c - >/dev/null || { echo "FAIL: pristine binary hash"; exit 1; }
PCOUNT=$(find x/usr/share/xtrace -name '*.proto' -size +0c | wc -l)
[ "$PCOUNT" = "$PROTO_COUNT" ] || { echo "FAIL: proto count $PCOUNT != $PROTO_COUNT"; exit 1; }
grep -qi 'GNU GENERAL PUBLIC LICENSE' x/usr/share/doc/$PKG/copyright || { echo "FAIL: license text missing"; exit 1; }

echo "== 6/7 GPL corresponding source (hashes from signed InRelease -> Sources.gz) =="
read SGZ_H SGZ_S <<< "$(meta_hash universe/source/Sources.gz)"
fetch_verified "dists/$RELEASE/universe/source/Sources.gz" "$SGZ_H" "$SGZ_S" Sources.gz
zcat Sources.gz > Sources.txt
awk -v pkg="$PKG" '
  /^Package: /{p=($2==pkg)} p&&/^Checksums-Sha256:/{c=1;next}
  c&&p&&/^ [0-9a-f]{64} /{print $1, $2, $3; next}
  c{exit}' Sources.txt > src.meta
[ -s src.meta ] || { echo "FAIL: source stanza not found"; exit 1; }
SRC_VER=$(awk -v pkg="$PKG" '/^Package: /{p=($2==pkg)} p&&/^Version: /{print $2; exit}' Sources.txt)
[ "$SRC_VER" = "$DEB_VER" ] || { echo "FAIL: source version $SRC_VER != binary version $DEB_VER"; exit 1; }
echo "source/binary version bound: $DEB_VER"
mkdir -p source
while read -r h sz fn; do
  fetch_verified "pool/universe/x/$PKG/$fn" "$h" "$sz" "source/$fn"
done < src.meta

echo "== 7/7 reproduce patched asset + emit tree + MANIFEST =="
mkdir -p out/proto
cp "$BIN" out/xtrace
cp x/usr/share/xtrace/*.proto out/proto/
cp x/usr/share/doc/$PKG/copyright out/COPYRIGHT
cp -r source out/source
python3 "$SCRIPT_DIR/xdiag_patch.py" out/xtrace out/xtrace "$PATCHED_ASSET_SHA256"
{
  echo "xdiag test assets - provenance and regeneration"
  echo " regenerated: $(date -u +%Y-%m-%dT%H:%M:%SZ) by ci/fetch-xdiag-tools.sh"
  echo " chain: pinned Ubuntu archive key fpr $ARCHIVE_KEY_FPR -> gpgv(InRelease $RELEASE)"
  echo " key source: Ubuntu Archive Automatic Signing Key (2018)"
  echo "        <ftpmaster@ubuntu.com>, $MIRROR/dists/$RELEASE/InRelease signer,"
  echo "        fetched via keyserver.ubuntu.com and trust-on-first-use pinned"
  echo "        by fingerprint above (verified against the 2018 archive key)"
  echo "        -> Packages.gz sha256 $PGZ_H -> deb $DEB_FILE sha256 $DEB_SHA256"
  echo "        -> pristine usr/bin/xtrace sha256 $PRISTINE_BIN_SHA256"
  echo " patch: single compiled-in '/usr/share/xtrace' NUL-terminated string replaced"
  echo "        by '/xdiag/proto' (NUL-padded, length-preserving) so decoders resolve"
  echo "        under the proot bind; patched asset sha256 $PATCHED_ASSET_SHA256"
  echo " arch: ELF x86-64 (CI emulator arch; NOT runnable on arm64 hosts)"
  echo " license: GPLv2 (COPYRIGHT bundled); corresponding source bundled under"
  echo "          source/ per Sources.gz sha256s from the same signed chain"
  echo " no-children (source-verified): fork/exec only in copyauth.c (disabled by -n)"
  echo "        and main.c execvp (only with a command operand; we pass none)"
  echo " files:"; (cd out && find . -type f ! -name MANIFEST.txt | sort | xargs sha256sum)
} > out/MANIFEST.txt
rm -rf "$DEST" && mkdir -p "$(dirname "$DEST")" && cp -r out "$DEST"
echo "OK: assets regenerated at $DEST"
