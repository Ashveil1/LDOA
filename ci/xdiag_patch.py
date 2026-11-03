#!/usr/bin/env python3
"""Reproduce the patched xtrace asset from the pristine deb binary.
Single anchor: the compiled-in '/usr/share/xtrace' NUL-terminated string
is replaced by '/xdiag/proto' NUL-padded to the same length (offsets stay
valid). Exactly one anchor must exist and the result must equal the
pinned sha256. Fail closed on anything else."""
import hashlib, sys
OLD = b"/usr/share/xtrace\x00"
NEW = b"/xdiag/proto\x00" + b"\x00" * (len(OLD) - len(b"/xdiag/proto") - 1)
def patch(data: bytes) -> bytes:
    if data.count(OLD) != 1:
        raise SystemExit("FAIL: patch anchor count %d != 1" % data.count(OLD))
    out = data.replace(OLD, NEW)
    assert len(out) == len(data)
    return out
if __name__ == "__main__":
    src, dst, want = sys.argv[1], sys.argv[2], sys.argv[3]
    out = patch(open(src, "rb").read())
    h = hashlib.sha256(out).hexdigest()
    if h != want:
        raise SystemExit("FAIL: patched sha256 %s != pinned %s" % (h, want))
    open(dst, "wb").write(out)
    print("patched asset reproduced, sha256", h)
