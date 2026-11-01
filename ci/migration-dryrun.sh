#!/usr/bin/env bash
# Emulator-only migration backup/restore dry run. Fixture state only.
# Old held debug APK -> run-as tar backup -> uninstall -> different-key install -> restore -> fidelity/boot checks.
set -uo pipefail
ADB="adb -s emulator-5554"
PKG="dev.phonelinux.proto"
DEV="/data/data/$PKG"
OLD_APK=/tmp/apks/old/app-debug.apk
NEW_APK=/tmp/apks/new/app-debug.apk
OUT="$GITHUB_WORKSPACE/out"; mkdir -p "$OUT"
R="$OUT/report.md"; : > "$R"
log(){ echo "$*" | tee -a "$R"; }
abort(){ log "ABORT: $*"; exit 1; }

log "# Migration dry-run (emulator API34 x86_64, fixture-only state)"
log "- old APK sha256: $(sha256sum "$OLD_APK" | cut -d' ' -f1)"
log "- new APK sha256: $(sha256sum "$NEW_APK" | cut -d' ' -f1)"

## 0. Tool verification (evidence first; no assumed mechanics)
{
  echo '## 0. Tool verification'
  adb version | head -1
  echo '--- adb help exec-* lines:'
  adb help 2>&1 | grep -iE 'exec-in|exec-out' || echo '(adb help lists no exec-*; functional probes decide)'
  echo '--- exec-out probe:'; $ADB exec-out echo probe-out 2>&1 | od -An -c | head -1
  echo '--- exec-in probe:'; echo probe-in | $ADB exec-in cat 2>&1 | od -An -c | head -1
  echo '--- exec-out stdin-forwarding probe:'; echo 'echo stdin-ok' | $ADB exec-out sh 2>&1 | head -1
  for t in tar find sha256sum stat readlink du df getfattr; do
    echo "--- toybox $t --help (head -2):"
    $ADB shell "toybox $t --help 2>&1 | head -2"
  done
  echo '--- toybox tar create/list smoke in /data/local/tmp:'
  $ADB shell 'cd /data/local/tmp && echo smoke > s.txt && toybox tar -cf s.tar s.txt && toybox tar -tf s.tar && rm -f s.txt s.tar'
  echo '--- toybox find -prune smoke:'
  $ADB shell 'cd /data/local/tmp && mkdir -p p/sub && touch p/sub/x p/y && toybox find p -path p/sub -prune -o -print; rm -rf p'
} >> "$R" 2>&1
EXEC_OUT_OK=0; $ADB exec-out echo p >/dev/null 2>&1 && EXEC_OUT_OK=1
EXEC_IN_OK=0;  echo p | $ADB exec-in cat >/dev/null 2>&1 && EXEC_IN_OK=1
STDIN_FWD_OK=0; [ "$(echo 'echo stdin-ok' | $ADB exec-out sh 2>/dev/null | tr -d '\r')" = "stdin-ok" ] && STDIN_FWD_OK=1
log "- functional: exec-out=$EXEC_OUT_OK exec-in=$EXEC_IN_OK exec-out-stdin-fwd=$STDIN_FWD_OK"
[ "$EXEC_OUT_OK" = 1 ] || abort "adb exec-out unusable; no verified stream-out mechanic"
[ "$STDIN_FWD_OK" = 1 ] || abort "adb exec-out does not forward stdin; seed/manifest mechanics unverified"

cat > /tmp/manifest.sh <<'MANI'
cd /data/data/dev.phonelinux.proto || exit 1
find . \( -path ./cache -o -path ./code_cache -o -path ./files/dl -o -path ./lib \) -prune -o -print | LC_ALL=C sort | while IFS= read -r p; do
  if [ -L "$p" ]; then echo "L|$(readlink "$p")|||$p";
  elif [ -d "$p" ]; then echo "D||$(stat -c '%a|%u' "$p" 2>/dev/null)||$p";
  elif [ -p "$p" ]; then echo "P||||$p";
  elif [ -S "$p" ]; then echo "S||||$p";
  elif [ -f "$p" ]; then echo "F|$(stat -c '%a|%s|%i|%u' "$p" 2>/dev/null)|$(sha256sum "$p" 2>/dev/null | cut -d' ' -f1)||$p"; fi
done
MANI

## 1. Install OLD build, one launch (natural prefs/dirs), stable stop
log '## 1. Install old + first launch + force-stop'
$ADB install -r "$OLD_APK" >>"$R" 2>&1 || abort 'old APK install failed'
$ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >>"$R" 2>&1
sleep 12
$ADB shell am force-stop $PKG
if $ADB exec-out run-as $PKG id > "$OUT/runas-after-forcestop.txt" 2>&1; then
  log "- run-as after force-stop: OK ($(cat "$OUT/runas-after-forcestop.txt"))"
else
  abort 'run-as unavailable after force-stop on old build'
fi

## 2. Enumerate + classify top-level app-data entries (explicit, logged)
$ADB exec-out run-as $PKG ls -la "$DEV" > "$OUT/top-level-old.txt" 2>&1
{
  echo '## 2. Top-level app-data entries (old build, post-launch)'
  echo 'Classification: BACKUP = files/ shared_prefs/ no_backup/ and any app_* dirs (app state).'
  echo 'EXCLUDE = ./cache (ephemeral), ./code_cache (JIT-generated, regenerated on device),'
  echo '  ./files/dl (installer downloads; app self-cleans + re-downloads, DistroInstaller.kt:109-110),'
  echo '  ./lib (installd-managed symlink into APK extract dir; stale after reinstall),'
  echo '  any socket at backup time (transient; app recreates).'
  sed 's/^/    /' "$OUT/top-level-old.txt"
} >> "$R"

## 3. Seed representative runtime state (fixture-only, app uid via run-as)
cat > /tmp/seed.sh <<'SEED'
set -e
cd /data/data/dev.phonelinux.proto/files || exit 1
mkdir -p distros/ubuntu/tmp distros/ubuntu/home/user distros/ubuntu/usr/bin distros/ubuntu/etc
echo install-marker-v1 > distros/ubuntu/.install-complete
echo hello-rootfs > distros/ubuntu/etc/motd
printf 'line1\nline2\n' > distros/ubuntu/home/user/notes.txt
dd if=/dev/zero of=distros/ubuntu/usr/bin/blob.bin bs=1024 count=37 2>/dev/null
echo hardlink-content > distros/ubuntu/etc/hardlink-a
ln distros/ubuntu/etc/hardlink-a distros/ubuntu/etc/hardlink-b
ln -s ../etc/motd distros/ubuntu/home/user/motd-link
{ echo '#!/system/bin/sh'; echo 'exit 0'; } > distros/ubuntu/usr/bin/suidtest
chmod 4755 distros/ubuntu/usr/bin/suidtest
echo secret-mode > distros/ubuntu/etc/shadowtest
chmod 600 distros/ubuntu/etc/shadowtest
mkfifo distros/ubuntu/tmp/testfifo
mkdir -p dl
echo partial-download-positive-control > dl/ubuntu.tar.gz.tmp
echo SEED-OK
SEED
$ADB exec-out run-as $PKG sh < /tmp/seed.sh >> "$R" 2>&1 || abort 'seeding failed'
grep -q SEED-OK "$R" || abort 'seed did not complete'
log '## 3. Seeded fixture: marker, plain files, 37KiB blob, hardlink pair, symlink, 4755 file, 600 file, fifo, dl/ exclusion positive-control'

## 4. Measure before streaming (device data size + host free)
{
  echo '## 4. Pre-stream measurements'
  echo '- device du -sm of app data:'; $ADB exec-out run-as $PKG du -sm "$DEV"
  echo '- host free space (MB, out dir):'; df -m "$OUT"
} >> "$R" 2>&1

## 5. Manifest A (device side, streamed to host)
$ADB exec-out run-as $PKG sh < /tmp/manifest.sh > "$OUT/manifestA.txt" 2> "$OUT/manifestA.err"
ACOUNT=$(wc -l < "$OUT/manifestA.txt")
log "## 5. Manifest A entries: $ACOUNT (stderr: $(wc -c < "$OUT/manifestA.err") bytes)"
[ "$ACOUNT" -gt 10 ] || abort 'manifest A suspiciously small'

## 5b. Explicit socket enumeration (excluded from archive, logged, never silent)
$ADB exec-out run-as $PKG sh -c "cd $DEV && find . -type s 2>/dev/null" > "$OUT/sockets.txt"
SOCK=$(tr -d '\r' < "$OUT/sockets.txt")
log "- sockets at backup time: ${SOCK:-none}"

## 6. Backup stream: exec-out run-as tar (binary-safe), explicit excludes
EX="--exclude=./cache* --exclude=./code_cache* --exclude=./files/dl* --exclude=./lib"
for s in $SOCK; do EX="$EX --exclude=$s"; done
log "## 6. Backup stream excludes: $EX"
$ADB exec-out run-as $PKG tar -C "$DEV" -czf- $EX . > "$OUT/backup.tar.gz" 2> "$OUT/backup-stderr.txt"
[ -s "$OUT/backup.tar.gz" ] || abort "backup stream empty (stderr: $(cat "$OUT/backup-stderr.txt"))"
BSHA=$(sha256sum "$OUT/backup.tar.gz" | cut -d' ' -f1)
log "- backup.tar.gz bytes=$(stat -c %s "$OUT/backup.tar.gz") sha256=$BSHA"

## 7. Host-side archive verification - ABORT GATE before uninstall
gzip -t "$OUT/backup.tar.gz" || abort 'gzip integrity failed'
tar -tzf "$OUT/backup.tar.gz" > "$OUT/members.txt" || abort 'tar listing failed'
grep -q 'files/dl/ubuntu.tar.gz.tmp' "$OUT/members.txt" && abort 'EXCLUSION CONTROL FAILED: dl/ content present in archive'
grep -q 'distros/ubuntu/.install-complete' "$OUT/members.txt" || abort 'install marker missing from archive'
grep -q 'etc/hardlink-a' "$OUT/members.txt" || abort 'seeded file missing from archive'
mkdir -p "$OUT/exA" && tar -xzf "$OUT/backup.tar.gz" -C "$OUT/exA"
cat > /tmp/verifyA.py <<'PYEOF'
import sys, os, hashlib
out = sys.argv[1]
ok = miss = bad = 0
for line in open(os.path.join(out, 'manifestA.txt')):
    parts = line.rstrip('\n').split('|')
    if len(parts) < 2 or parts[0] != 'F':
        continue
    path = parts[-1]; dev_sha = parts[5]
    hp = os.path.join(out, 'exA', path.lstrip('./'))
    if not os.path.isfile(hp):
        miss += 1; print('MISSING-IN-ARCHIVE', path); continue
    h = hashlib.sha256(open(hp, 'rb').read()).hexdigest()
    if h != dev_sha:
        bad += 1; print('HASH-MISMATCH', path, dev_sha, h); continue
    ok += 1
print(f'ARCHIVE-VS-DEVICE files ok={ok} missing={miss} mismatched={bad}')
sys.exit(0 if (miss == 0 and bad == 0 and ok > 0) else 1)
PYEOF
python3 /tmp/verifyA.py "$OUT" > "$OUT/verifyA.txt" 2>&1
cat "$OUT/verifyA.txt" >> "$R"
tail -1 "$OUT/verifyA.txt" | grep -q 'missing=0 mismatched=0' || abort 'archive-vs-device hash verification failed - uninstall NOT performed; backup retained'

## 8. Uninstall old, install NEW (different signing key), DO NOT launch
log '## 8. Uninstall old -> install new (f7154ab, different per-run debug key) - no launch before restore'
$ADB uninstall $PKG >>"$R" 2>&1 || abort 'uninstall failed'
$ADB install "$NEW_APK" >>"$R" 2>&1 || abort 'new APK install failed'
if $ADB exec-out run-as $PKG ls -la "$DEV" > "$OUT/top-level-new-prelaunch.txt" 2>&1; then
  log '- run-as works on never-launched new build'
else
  log '- NOTE: run-as failed on never-launched new build'
fi
sed 's/^/    /' "$OUT/top-level-new-prelaunch.txt" >> "$R"

## 9. Restore via verified stream mechanic
log '## 9. Restore'
if [ "$EXEC_IN_OK" = 1 ]; then
  cat "$OUT/backup.tar.gz" | $ADB exec-in run-as $PKG tar -xzf- -C "$DEV" 2> "$OUT/restore-stderr.txt"
  log '- mechanic: adb exec-in stream (probe-verified)'
else
  $ADB push "$OUT/backup.tar.gz" /data/local/tmp/mig.tgz >>"$R" 2>&1
  $ADB shell df -m /data/local/tmp >>"$R" 2>&1
  $ADB shell run-as $PKG tar -xzf /data/local/tmp/mig.tgz -C "$DEV" 2> "$OUT/restore-stderr.txt"
  $ADB shell rm -f /data/local/tmp/mig.tgz
  log '- mechanic: push /data/local/tmp + extract (exec-in probe failed)'
fi
log "- restore stderr bytes: $(wc -c < "$OUT/restore-stderr.txt")"

## 10. Manifest B + per-item diff (uid change expected, not a failure)
$ADB exec-out run-as $PKG sh < /tmp/manifest.sh > "$OUT/manifestB.txt" 2> "$OUT/manifestB.err"
cat > /tmp/diff.py <<'PYEOF'
import sys
def load(p):
    m = {}
    for line in open(p):
        parts = line.rstrip('\n').split('|')
        t = parts[0]; path = parts[-1]
        if t == 'F':
            m[path] = ('F', parts[1], parts[2], parts[3], parts[4], parts[5])
        elif t == 'L':
            m[path] = ('L', parts[1])
        elif t == 'D':
            m[path] = ('D', parts[2], parts[3])
        else:
            m[path] = (t,)
    return m
out = sys.argv[1]
A = load(out + '/manifestA.txt'); B = load(out + '/manifestB.txt')
only_a = sorted(set(A) - set(B)); only_b = sorted(set(B) - set(A))
content_bad = mode_bad = link_bad = ok = 0
for p in sorted(set(A) & set(B)):
    a, b = A[p], B[p]
    if a[0] != b[0]:
        print('TYPE-CHANGE', p, a[0], '->', b[0]); content_bad += 1; continue
    if a[0] == 'F':
        if a[5] != b[5]: print('SHA-MISMATCH', p); content_bad += 1; continue
        if a[1] != b[1]: print('MODE-CHANGE', p, a[1], '->', b[1]); mode_bad += 1; continue
        ok += 1
    elif a[0] == 'L':
        if a[1] != b[1]: print('LINK-CHANGE', p, a[1], '->', b[1]); link_bad += 1; continue
        ok += 1
    elif a[0] == 'D':
        if a[1] != b[1]: print('DIRMODE-CHANGE', p, a[1], '->', b[1]); mode_bad += 1; continue
        ok += 1
    else:
        ok += 1
ha = [p for p in A if A[p][0] == 'F' and p.endswith('hardlink-a')]
hb = [p for p in A if A[p][0] == 'F' and p.endswith('hardlink-b')]
if ha and hb and ha[0] in B and hb[0] in B:
    a_same = A[ha[0]][3] == A[hb[0]][3]
    b_same = B[ha[0]][3] == B[hb[0]][3]
    print(f'HARDLINK-PAIR inode-shared: pre={a_same} post={b_same}')
else:
    print('HARDLINK-PAIR not found in both manifests')
uids_a = sorted({A[p][4] for p in A if A[p][0] == 'F'})
uids_b = sorted({B[p][4] for p in B if B[p][0] == 'F'})
print(f'UIDS pre={uids_a} post={uids_b} (change expected: new app uid)')
print(f'ENTRIES pre={len(A)} post={len(B)} onlyPre={len(only_a)} onlyPost={len(only_b)}')
for p in only_a[:50]: print('ONLY-PRE', p)
for p in only_b[:50]: print('ONLY-POST', p)
print(f'RESULT ok={ok} content_bad={content_bad} mode_bad={mode_bad} link_bad={link_bad}')
PYEOF
python3 /tmp/diff.py "$OUT" > "$OUT/diff.txt" 2>&1
log '## 10. Fidelity diff (A=pre-backup device, B=post-restore device)'
sed 's/^/    /' "$OUT/diff.txt" >> "$R"
grep -q 'content_bad=0 mode_bad=0 link_bad=0' "$OUT/diff.txt" || log '- FIDELITY FAILURES PRESENT (see diff)'

## 11. Boot/integrity on restored data (app-level; no GNOME perf rerun)
log '## 11. Boot/integrity (restored data)'
$ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >>"$R" 2>&1
sleep 15
{
  echo '- pidof:'; $ADB shell pidof $PKG
  echo '- install-complete marker:'; $ADB shell run-as $PKG cat $DEV/files/distros/ubuntu/.install-complete
  echo '- shared_prefs present:'; $ADB shell run-as $PKG ls $DEV/shared_prefs
  echo '- FATAL/AndroidRuntime lines for pkg:'
  $ADB logcat -d | grep -E 'FATAL|AndroidRuntime' | grep $PKG | head -10 || echo '(none)'
} >> "$R" 2>&1
log '## DONE'
