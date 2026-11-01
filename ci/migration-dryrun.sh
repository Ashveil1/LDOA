#!/usr/bin/env bash
# Emulator-only migration backup/restore dry run. Fixture state only.
# Old held debug APK -> run-as tar stream-out -> host verify (abort gate)
# -> uninstall -> different-key debug install -> restore -> fidelity/boot checks.
# Mechanics: exec-out for all device->host streams (probe-verified);
# helper scripts pushed to /data/local/tmp (no stdin-forwarding dependency);
# restore = push to /data/local/tmp + extract (exec-in hangs on this adbd -
# stdin EOF never reaches the command - so it is probe-only evidence here).
# Every adb call is timeout-wrapped: fail fast, never hang.
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

## 0. Tool verification (evidence first; timeout-guarded; no assumed mechanics)
{
  echo '## 0. Tool verification'
  adb version | head -1
  echo '--- adb help exec-* lines:'
  adb help 2>&1 | grep -iE 'exec-in|exec-out' || echo '(adb help lists no exec-*; functional probes decide)'
  echo '--- exec-out probe:'; timeout 15 $ADB exec-out echo probe-out 2>&1 | od -An -c | head -1; echo "exec-out rc=$?"
  echo '--- exec-in probe (15s cap; hang expected on raw service without stdin EOF):'
  echo probe-in | timeout 15 $ADB exec-in cat 2>&1 | od -An -c | head -1; echo "exec-in rc=$? (124=timeout/hang)"
  for t in tar find sha256sum stat readlink du df getfattr; do
    echo "--- toybox $t --help (head -2):"
    timeout 15 $ADB shell "toybox $t --help 2>&1 | head -2"
  done
  echo '--- toybox tar create/list smoke in /data/local/tmp:'
  timeout 15 $ADB shell 'cd /data/local/tmp && echo smoke > s.txt && toybox tar -cf s.tar s.txt && toybox tar -tf s.tar && rm -f s.txt s.tar'
  echo '--- toybox find -prune smoke:'
  timeout 15 $ADB shell 'cd /data/local/tmp && mkdir -p p/sub && touch p/sub/x p/y && toybox find p -path p/sub -prune -o -print; rm -rf p'
} >> "$R" 2>&1
EXEC_OUT_OK=0; timeout 15 $ADB exec-out echo p >/dev/null 2>&1 && EXEC_OUT_OK=1
log "- functional: exec-out=$EXEC_OUT_OK"
[ "$EXEC_OUT_OK" = 1 ] || abort "adb exec-out unusable; no verified stream-out mechanic"

cat > /tmp/ldoa-manifest.sh <<'MANI'
cd /data/data/dev.phonelinux.proto || exit 1
find . \( -path ./cache -o -path ./code_cache -o -path ./files/dl -o -path ./lib \) -prune -o -print | LC_ALL=C sort | while IFS= read -r p; do
  if [ -L "$p" ]; then echo "L|$(readlink "$p")|||$p";
  elif [ -d "$p" ]; then echo "D||$(stat -c '%a|%u' "$p" 2>/dev/null)||$p";
  elif [ -p "$p" ]; then echo "P||||$p";
  elif [ -S "$p" ]; then echo "S||||$p";
  elif [ -f "$p" ]; then echo "F|$(stat -c '%a|%s|%i|%u' "$p" 2>/dev/null)|$(sha256sum "$p" 2>/dev/null | cut -d' ' -f1)||$p"; fi
done
MANI
cat > /tmp/ldoa-seed.sh <<'SEED'
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
timeout 30 $ADB push /tmp/ldoa-manifest.sh /data/local/tmp/ldoa-manifest.sh >>"$R" 2>&1
timeout 30 $ADB push /tmp/ldoa-seed.sh /data/local/tmp/ldoa-seed.sh >>"$R" 2>&1
timeout 15 $ADB shell chmod 644 /data/local/tmp/ldoa-manifest.sh /data/local/tmp/ldoa-seed.sh

## 1. Install OLD build, one launch (natural prefs/dirs), stable stop
log '## 1. Install old + first launch + force-stop'
timeout 300 $ADB install -r "$OLD_APK" >>"$R" 2>&1 || abort 'old APK install failed'
timeout 30 $ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >>"$R" 2>&1
sleep 12
timeout 30 $ADB shell am force-stop $PKG
if timeout 30 $ADB exec-out run-as $PKG id > "$OUT/runas-after-forcestop.txt" 2>&1; then
  log "- run-as after force-stop: OK ($(cat "$OUT/runas-after-forcestop.txt"))"
else
  abort 'run-as unavailable after force-stop on old build'
fi

## 2. Enumerate + classify top-level app-data entries (explicit, logged)
timeout 30 $ADB exec-out run-as $PKG ls -la "$DEV" > "$OUT/top-level-old.txt" 2>&1
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
timeout 60 $ADB exec-out run-as $PKG sh /data/local/tmp/ldoa-seed.sh >> "$R" 2>&1 || abort 'seeding failed'
grep -q SEED-OK "$R" || abort 'seed did not complete'
log '## 3. Seeded fixture: marker, plain files, 37KiB blob, hardlink pair, symlink, 4755 file, 600 file, fifo, dl/ exclusion positive-control'

## 4. Measure before streaming (device data size + host free)
{
  echo '## 4. Pre-stream measurements'
  echo '- device du -sm of app data:'; timeout 60 $ADB exec-out run-as $PKG du -sm "$DEV"
  echo '- host free space (MB, out dir):'; df -m "$OUT"
} >> "$R" 2>&1

## 5. Manifest A (device side, streamed to host via exec-out)
timeout 300 $ADB exec-out run-as $PKG sh /data/local/tmp/ldoa-manifest.sh > "$OUT/manifestA.txt" 2> "$OUT/manifestA.err"
ACOUNT=$(wc -l < "$OUT/manifestA.txt")
log "## 5. Manifest A entries: $ACOUNT (stderr: $(wc -c < "$OUT/manifestA.err") bytes)"
[ "$ACOUNT" -gt 10 ] || abort 'manifest A suspiciously small'

## 5b. Explicit socket enumeration (excluded from archive, logged, never silent)
timeout 60 $ADB exec-out run-as $PKG sh -c "cd $DEV && find . -type s 2>/dev/null" > "$OUT/sockets.txt"
SOCK=$(tr -d '\r' < "$OUT/sockets.txt")
log "- sockets at backup time: ${SOCK:-none}"

## 6. Backup stream: exec-out run-as tar (binary-safe), explicit excludes
EX="--exclude=./cache* --exclude=./code_cache* --exclude=./files/dl* --exclude=./lib"
for s in $SOCK; do EX="$EX --exclude=$s"; done
log "## 6. Backup stream excludes: $EX"
timeout 600 $ADB exec-out run-as $PKG tar -C "$DEV" -czf- $EX . > "$OUT/backup.tar.gz" 2> "$OUT/backup-stderr.txt"
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
timeout 60 $ADB uninstall $PKG >>"$R" 2>&1 || abort 'uninstall failed'
timeout 300 $ADB install "$NEW_APK" >>"$R" 2>&1 || abort 'new APK install failed'
if timeout 30 $ADB exec-out run-as $PKG ls -la "$DEV" > "$OUT/top-level-new-prelaunch.txt" 2>&1; then
  log '- run-as works on never-launched new build'
else
  log '- NOTE: run-as failed on never-launched new build'
fi
sed 's/^/    /' "$OUT/top-level-new-prelaunch.txt" >> "$R"

## 9. Restore: push tarball to /data/local/tmp (df-checked) + run-as extract.
## exec-in is NOT used: probe showed it hangs on this adbd (stdin EOF never
## reaches the command); push+extract is the verified mechanic here.
log '## 9. Restore (push + extract; exec-in probe result documented in step 0)'
timeout 60 $ADB push "$OUT/backup.tar.gz" /data/local/tmp/ldoa-mig.tgz >>"$R" 2>&1 || abort 'backup push failed'
{
  echo '- /data/local/tmp df before extract:'; timeout 15 $ADB shell df -m /data/local/tmp
  echo '- target df:'; timeout 15 $ADB shell df -m "$DEV"
} >> "$R" 2>&1
timeout 600 $ADB shell run-as $PKG tar -xzf /data/local/tmp/ldoa-mig.tgz -C "$DEV" 2> "$OUT/restore-stderr.txt"
RRC=$?
timeout 15 $ADB shell rm -f /data/local/tmp/ldoa-mig.tgz
log "- restore rc=$RRC stderr bytes: $(wc -c < "$OUT/restore-stderr.txt")"
[ "$RRC" = 0 ] || abort "restore extract failed rc=$RRC"

## 10. Manifest B + per-item diff (uid change expected, not a failure)
timeout 300 $ADB exec-out run-as $PKG sh /data/local/tmp/ldoa-manifest.sh > "$OUT/manifestB.txt" 2> "$OUT/manifestB.err"
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
timeout 30 $ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >>"$R" 2>&1
sleep 15
{
  echo '- pidof:'; timeout 15 $ADB shell pidof $PKG
  echo '- install-complete marker:'; timeout 15 $ADB shell run-as $PKG cat $DEV/files/distros/ubuntu/.install-complete
  echo '- shared_prefs present:'; timeout 15 $ADB shell run-as $PKG ls $DEV/shared_prefs
  echo '- FATAL/AndroidRuntime lines for pkg:'
  timeout 30 $ADB logcat -d | grep -E 'FATAL|AndroidRuntime' | grep $PKG | head -10 || echo '(none)'
} >> "$R" 2>&1
timeout 15 $ADB shell rm -f /data/local/tmp/ldoa-manifest.sh /data/local/tmp/ldoa-seed.sh
log '## DONE'
