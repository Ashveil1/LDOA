import base64, collections, os, re, sys

text = open(sys.argv[1], encoding="utf-8", errors="replace").read()

# Channel 1: logcat lines "I PHONESHOT: name|seq|total|chunk" (any prefix)
chunks = collections.defaultdict(dict)
totals = {}
lc = re.compile(r"PHONESHOT[^:]*:\s*([A-Za-z0-9-]+)\|(\d{4})\|(\d{4})\|(\S+)")
for m in lc.finditer(text):
    name, seq, total, val = m.groups()
    chunks[name][int(seq)] = val
    totals[name] = int(total)

# Channel 2: instrumentation status "INSTRUMENTATION_STATUS: shot_b64_name_SSSS=chunk"
if not chunks:
    st = re.compile(r"INSTRUMENTATION_STATUS:\s*shot_b64_([A-Za-z0-9-]+?)_(\d{4}|count)=(\S*)")
    for m in st.finditer(text):
        name, seq, val = m.groups()
        if seq == "count":
            totals[name] = int(val)
        else:
            chunks[name][int(seq)] = val

os.makedirs(sys.argv[2], exist_ok=True)
if not chunks:
    print("no screenshot chunks found", file=sys.stderr)
    sys.exit(0)
for name, parts in sorted(chunks.items()):
    seqs = sorted(parts)
    expected = totals.get(name)
    if expected is not None and seqs != list(range(expected)):
        print(f"shot {name}: INCOMPLETE {len(parts)}/{expected} chunks", file=sys.stderr)
        continue
    data = base64.b64decode("".join(parts[s] for s in seqs))
    out = os.path.join(sys.argv[2], name + ".jpg")
    with open(out, "wb") as fh:
        fh.write(data)
    print(f"shot {name}: wrote {out} ({len(data)} bytes, {len(parts)} chunks)")
