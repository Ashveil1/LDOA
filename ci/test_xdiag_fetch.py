#!/usr/bin/env python3
"""Fixture tests for the xdiag fetch/patch tooling.

Part 1 (no network): the shared patcher must reproduce the pinned asset
ONLY from a blob with exactly one anchor, and fail closed otherwise.

Part 2 (local fixture mirror, still no network): the fetch script's
signed-chain logic is exercised end to end against a file:// mirror
signed by a THROWAWAY test key via the XDIAG_TEST_* hooks - happy path,
wrong-signer rejection, source/binary version mismatch, and the DEST
guard. These hooks exist only for this test; production uses the pins."""
import gzip, hashlib, io, os, shutil, subprocess, sys, tarfile, tempfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from xdiag_patch import patch, OLD

# ---------- Part 1: patcher fixtures ----------
def expect_ok(blob):
    out = patch(blob)
    assert len(out) == len(blob)
    assert b"/xdiag/proto\x00" in out and OLD not in out
    return out

def expect_fail(blob, why):
    try:
        patch(blob)
    except SystemExit:
        return
    raise AssertionError("patch must fail closed: " + why)

base = b"\x7fELF...." + OLD + b"....middle...."
t = expect_ok(base)
assert hashlib.sha256(base).hexdigest() != hashlib.sha256(t).hexdigest()
expect_fail(b"no anchor here", "zero anchors")
expect_fail(OLD + b"x" + OLD, "two anchors")
expect_fail(b"", "empty")

# ---------- Part 2: signed-chain integration fixture ----------
CI = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(CI, "fetch-xdiag-tools.sh")

def sh(*args, **kw):
    return subprocess.run(*args, shell=isinstance(args[0], str),
                          capture_output=True, text=True, **kw)

def make_key(home, uid):
    r = sh(["gpg", "--homedir", home, "--batch", "--pinentry-mode", "loopback",
            "--passphrase", "", "--quick-generate-key", uid, "rsa2048", "sign", "never"])
    assert r.returncode == 0, r.stderr
    r = sh(["gpg", "--homedir", home, "--with-colons", "--list-keys"])
    fpr = [l.split(":")[9] for l in r.stdout.splitlines() if l.startswith("fpr:")][0]
    return fpr

def tar_gz(files):  # {arcname: bytes} -> .tar.gz bytes
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tf:
        for name, data in files.items():
            ti = tarfile.TarInfo(name); ti.size = len(data)
            tf.addfile(ti, io.BytesIO(data))
    return buf.getvalue()

def ar_member(name, data):
    hdr = (name.ljust(16) + "0".ljust(12) + "0".ljust(6) + "0".ljust(6) +
           "100644".ljust(8) + str(len(data)).ljust(10) + "`\n").encode()
    return hdr + data + (b"\n" if len(data) % 2 else b"")

def build_deb(bin_bytes, proto_count):
    data = {"usr/bin/xtrace": bin_bytes,
            "usr/share/doc/xtrace/copyright": b"GNU GENERAL PUBLIC LICENSE\nversion 2\n"}
    for i in range(proto_count):
        data["usr/share/xtrace/p%d.proto" % i] = b"fixture proto %d\n" % i
    control = tar_gz({"control": b"Package: xtrace\nVersion: 1.4.0-1\n"})
    return (b"!<arch>\n" + ar_member("debian-binary", b"2.0\n") +
            ar_member("control.tar.gz", control) +
            ar_member("data.tar.gz", tar_gz(data)))

def sha(b): return hashlib.sha256(b).hexdigest()

def build_mirror(root, sign_home, sign_fpr, deb_ver="1.4.0-1", src_ver="1.4.0-1"):
    """Returns (mirror_dir, env dict) for a mirror signed by sign_fpr's key."""
    proto_count = 2
    bin_bytes = b"\x7fELF fixture" + OLD + b" tail"
    deb = build_deb(bin_bytes, proto_count)
    src = b"fixture source tarball\n"
    m = os.path.join(root, "mirror")
    pkg_dir = os.path.join(m, "pool/universe/x/xtrace")
    os.makedirs(pkg_dir)
    deb_name = "xtrace_%s_amd64.deb" % deb_ver
    open(os.path.join(pkg_dir, deb_name), "wb").write(deb)
    open(os.path.join(pkg_dir, "xtrace_%s.orig.tar.gz" % src_ver), "wb").write(src)
    packages = ("Package: xtrace\nVersion: %s\nFilename: pool/universe/x/xtrace/%s\n"
                "Size: %d\nSHA256: %s\n\n" % (deb_ver, deb_name, len(deb), sha(deb))).encode()
    sources = ("Package: xtrace\nVersion: %s\nChecksums-Sha256:\n %s %d xtrace_%s.orig.tar.gz\n\n"
               % (src_ver, sha(src), len(src), src_ver)).encode()
    pgz, sgz = gzip.compress(packages), gzip.compress(sources)
    rel = os.path.join(m, "dists/noble")
    os.makedirs(os.path.join(rel, "universe/binary-amd64"))
    os.makedirs(os.path.join(rel, "universe/source"))
    open(os.path.join(rel, "universe/binary-amd64/Packages.gz"), "wb").write(pgz)
    open(os.path.join(rel, "universe/source/Sources.gz"), "wb").write(sgz)
    body = "Origin: fixture\nSuite: noble\nSHA256:\n"
    for path, blob in (("universe/binary-amd64/Packages.gz", pgz),
                       ("universe/source/Sources.gz", sgz)):
        body += " %s %d %s\n" % (sha(blob), len(blob), path)
    ir = os.path.join(rel, "InRelease")
    open(ir, "w").write(body)
    r = sh(["gpg", "--homedir", sign_home, "--batch", "--pinentry-mode", "loopback",
            "--passphrase", "", "--local-user", sign_fpr, "--clearsign", "--output", ir + ".s", ir])
    assert r.returncode == 0, r.stderr
    os.replace(ir + ".s", ir)
    patched = patch(bin_bytes)
    env = dict(os.environ,
               XDIAG_FIXTURE_MODE="1",  # explicit opt-in; ambient env alone is stripped
               XDIAG_MIRROR="file://" + m,
               XDIAG_TEST_PRISTINE_SHA256=sha(bin_bytes),
               XDIAG_TEST_PATCHED_SHA256=sha(patched),
               XDIAG_TEST_PROTO_COUNT=str(proto_count))
    return m, env

def run_script(env, dest):
    return sh(["bash", SCRIPT, dest], env=env)

# 0) OPT-IN ORDERING REGRESSION: the fixture-gate must strip ambient
# overrides BEFORE any variable is derived from them. Static pin: the
# gating block precedes the MIRROR= assignment in the script source.
_src = open(SCRIPT).read()
assert _src.index("XDIAG_FIXTURE_MODE") < _src.index("MIRROR=${XDIAG_MIRROR"), \
    "fixture gating must precede the first derived assignment"
# Behavioral pin (LIVE e2e; opt-in via XDIAG_LIVE_E2E=1 so the local
# fixture suite never depends on the network): an ambient XDIAG_MIRROR
# WITHOUT fixture opt-in must NOT reach the script - with a garbage
# mirror the run would die at the first curl; with the strip in place it
# proceeds against the real archive.
if os.environ.get("XDIAG_LIVE_E2E") == "1":
    _garbage = dict(os.environ, XDIAG_MIRROR="file:///nonexistent-garbage-mirror")
    _garbage.pop("XDIAG_FIXTURE_MODE", None)
    r = subprocess.run(["bash", SCRIPT, os.path.join(tempfile.mkdtemp(prefix="xdiag-ambient-"), "out")],
                       env=_garbage, capture_output=True, text=True, timeout=600)
    assert "nonexistent-garbage-mirror" not in (r.stdout + r.stderr), \
        "ambient XDIAG_MIRROR leaked into a non-fixture run"
    assert r.returncode == 0, "non-fixture run must ignore ambient overrides:\n" + r.stdout + r.stderr

tmp = tempfile.mkdtemp(prefix="xdiag-fetch-test-")
try:
    # right key + wrong key, keyring carrying BOTH so gpgv validates and
    # the signer-fingerprint check is what must reject the wrong one
    home_a, home_b = os.path.join(tmp, "ga"), os.path.join(tmp, "gb")
    os.makedirs(home_a); os.makedirs(home_b)
    os.chmod(home_a, 0o700); os.chmod(home_b, 0o700)
    fpr_a = make_key(home_a, "xdiag-test-right <r@t>")
    fpr_b = make_key(home_b, "xdiag-test-wrong <w@t>")
    keyring = os.path.join(tmp, "kr.gpg")
    with open(keyring, "wb") as out:
        for home in (home_a, home_b):
            r = subprocess.run(["gpg", "--homedir", home, "--export"],
                               capture_output=True)  # binary key material
            assert r.returncode == 0
            out.write(r.stdout)

    # 1) happy path: right signer, matching versions, allowed DEST
    m, env = build_mirror(tmp, home_a, fpr_a)
    env.update(XDIAG_TEST_KEY_FPR=fpr_a, XDIAG_TEST_KEYRING=keyring)
    dest = os.path.join(tmp, "assets-out")
    r = run_script(env, dest)
    assert r.returncode == 0, "happy path failed:\n" + r.stdout + r.stderr
    patched = open(os.path.join(dest, "xtrace"), "rb").read()
    assert sha(patched) == env["XDIAG_TEST_PATCHED_SHA256"]
    man = open(os.path.join(dest, "MANIFEST.txt")).read()
    assert "trust-on-first-use pinned" in man and fpr_a in man
    assert os.path.isdir(os.path.join(dest, "source"))

    # 2) wrong signer must FAIL even though gpgv validates the signature
    m2, env2 = build_mirror(os.path.join(tmp, "w"), home_b, fpr_b)
    env2.update(XDIAG_TEST_KEY_FPR=fpr_a, XDIAG_TEST_KEYRING=keyring)
    r = run_script(env2, os.path.join(tmp, "out-wrong"))
    assert r.returncode != 0, "wrong signer must fail"
    assert "NOT the pinned archive key" in r.stdout

    # 3) source/binary version mismatch must FAIL
    m3, env3 = build_mirror(os.path.join(tmp, "v"), home_a, fpr_a, src_ver="9.9-1")
    env3.update(XDIAG_TEST_KEY_FPR=fpr_a, XDIAG_TEST_KEYRING=keyring)
    r = run_script(env3, os.path.join(tmp, "out-ver"))
    assert r.returncode != 0, "version mismatch must fail"
    assert "source version" in r.stdout

    # 4) DEST guard: a path outside the assets dir and /tmp must be refused
    evil = os.path.expanduser("~/xdiag-evil-dest")
    r = run_script(env, evil)
    assert r.returncode != 0, "DEST guard must refuse " + evil
    assert "refusing to replace" in r.stdout
    assert not os.path.exists(evil)

    # 5) hash-tampered deb must FAIL (sha256 from signed metadata)
    m4, env4 = build_mirror(os.path.join(tmp, "t"), home_a, fpr_a)
    env4.update(XDIAG_TEST_KEY_FPR=fpr_a, XDIAG_TEST_KEYRING=keyring)
    dp = os.path.join(m4, "pool/universe/x/xtrace/xtrace_1.4.0-1_amd64.deb")
    open(dp, "ab").write(b"tamper")  # size+hash now wrong
    r = run_script(env4, os.path.join(tmp, "out-tamper"))
    assert r.returncode != 0, "tampered deb must fail"
finally:
    shutil.rmtree(tmp, ignore_errors=True)

print("xdiag fetch/patch fixture tests PASS")
