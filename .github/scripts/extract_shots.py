import base64, collections, os, re, sys

log = open(sys.argv[1], encoding="utf-8", errors="replace").read()
pat = re.compile(r"INSTRUMENTATION_STATUS:\s*shot_b64_([A-Za-z0-9-]+?)_(\d{4}|count)=(\S*)")
chunks = collections.defaultdict(dict)
counts = {}
for m in pat.finditer(log):
    name, seq, val = m.groups()
    if seq == "count":
        counts[name] = int(val)
    else:
        chunks[name][int(seq)] = val

os.makedirs(sys.argv[2], exist_ok=True)
if not chunks:
    print("no shot_b64 status lines found", file=sys.stderr)
    sys.exit(0)
for name, parts in sorted(chunks.items()):
    seqs = sorted(parts)
    expected = counts.get(name)
    if expected is not None and seqs != list(range(expected)):
        print(f"shot {name}: INCOMPLETE {len(parts)}/{expected} chunks", file=sys.stderr)
        continue
    data = base64.b64decode("".join(parts[s] for s in seqs))
    out = os.path.join(sys.argv[2], name + ".jpg")
    with open(out, "wb") as fh:
        fh.write(data)
    print(f"shot {name}: wrote {out} ({len(data)} bytes, {len(parts)} chunks)")
