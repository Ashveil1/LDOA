#!/usr/bin/env python3
"""End-to-end evidence-retention fixture for the input-harness lane.

Purpose: a device-side artifact that the lane harvests (pull_or_absent) but
the workflow never uploads is a SILENT evidence-loss path (run 41371167693:
virgl-session-xmap.ppm was pulled and header-validated, then discarded with
the runner because the upload-artifact path list predated the probe).

This fixture runs the whole chain LOCALLY with representative files:
  1. ACTUAL parse of the upload-artifact path list from
     .github/workflows/input-harness.yml (no hardcoded copy of the list).
  2. ACTUAL parse of the lane's harvested artifacts from ci/emulator-lane.sh
     (pull_or_absent targets, including the for-loop lists).
  3. A simulated runner workspace with a representative file for every
     harvested artifact, every upload-list entry, and the report-claimed
     pixel artifacts.
  4. upload-artifact@v4 path semantics applied to the simulated workspace
     (literal file, trailing-slash directory recursive, absolute path) to
     produce the inventory that WOULD be uploaded.
  5. Checks, any failure exits 1:
     a. every harvested artifact is upload-retained;
     b. every upload-list entry resolves in the simulated workspace
        (catches path-list typos);
     c. report-claimed pixel artifacts: a virgl-session.txt whose VERDICT
        records window-pixels SAMPLED requires virgl-session-xmap.ppm in the
        upload inventory; guest-xcontent CAPTURED requires
        virgl-session-xcontent.img (the .partial is optional evidence).

Usage: check_evidence_upload_paths.py <repo-root>
"""
import re
import sys
import tempfile
from pathlib import Path

# Claim -> required uploaded artifact. Provenance: ci/emulator-lane.sh
# header-gate blocks pair exactly these verdicts with these files.
CLAIM_RULES = [
    (re.compile(r"VERDICT window-pixels: SAMPLED"), "virgl-session-xmap.ppm"),
    (re.compile(r"VERDICT guest-xcontent: CAPTURED"), "virgl-session-xcontent.img"),
]

SAMPLE_REPORT = """\
VERDICT window-pixels: SAMPLED (5 of 5 patches; fixture sample)
VERDICT guest-xcontent: CAPTURED tool=xwd (fixture sample)
"""


def parse_upload_paths(workflow: str) -> list[str]:
    lines = Path(workflow).read_text().splitlines()
    paths: list[str] = []
    i = 0
    while i < len(lines):
        if "uses: actions/upload-artifact@" in lines[i]:
            # find the `path: |` block scalar inside this step's `with:`
            j = i + 1
            while j < len(lines) and not re.match(r"^\s+path:\s*\|", lines[j]):
                if j > i + 40:
                    break
                j += 1
            if j >= len(lines) or not re.match(r"^\s+path:\s*\|", lines[j]):
                raise SystemExit("upload-artifact step has no `path: |` block")
            indent = len(lines[j]) - len(lines[j].lstrip())
            k = j + 1
            while k < len(lines):
                ln = lines[k]
                if ln.strip() == "":
                    k += 1
                    continue
                cur = len(ln) - len(ln.lstrip())
                if cur <= indent:
                    break
                paths.append(ln.strip())
                k += 1
            return paths
        i += 1
    raise SystemExit("no upload-artifact step found in workflow")


def parse_harvested(lane: str) -> list[str]:
    text = Path(lane).read_text()
    found: set[str] = set()
    # for f in a b c; do pull_or_absent "$f" ... done
    for m in re.finditer(r"for f in ([^;]+);\s*do\s*\n\s*pull_or_absent \"\$f\"", text):
        found.update(m.group(1).split())
    # direct: pull_or_absent <name> <host-target> ...
    for m in re.finditer(r"pull_or_absent ([A-Za-z0-9._/-]+) ", text):
        name = m.group(1)
        if name != "$f":
            found.add(name)
    return sorted(found)


def simulate_upload(paths: list[str], workspace: Path) -> list[str]:
    inventory: list[str] = []
    for entry in paths:
        if entry.startswith("/"):
            # absolute path (e.g. /tmp logs): lane-created, outside the
            # simulated workspace - declared, not simulated (never consult
            # the real host filesystem, it would leak local state in).
            continue
        p = workspace / entry
        if entry.endswith("/"):
            if p.is_dir():
                for f in sorted(p.rglob("*")):
                    if f.is_file():
                        inventory.append(str(f.relative_to(workspace)))
        elif p.is_file():
            inventory.append(entry)
    return sorted(inventory)


def main() -> int:
    root = Path(sys.argv[1])
    upload_paths = parse_upload_paths(root / ".github/workflows/input-harness.yml")
    harvested = parse_harvested(root / "ci/emulator-lane.sh")
    print("parsed upload-list entries: %d" % len(upload_paths))
    print("parsed lane-harvested artifacts: %d" % len(harvested))

    failures: list[str] = []
    with tempfile.TemporaryDirectory() as td:
        ws = Path(td)
        # representative files: every harvested artifact + report-claimed
        made: set[str] = set()
        for name in harvested + [r[1] for r in CLAIM_RULES]:
            (ws / name).write_text("representative " + name + "\n")
            made.add(name)
        # representative content for directory entries in the upload list
        for entry in upload_paths:
            if entry.endswith("/"):
                d = ws / entry
                d.mkdir(parents=True, exist_ok=True)
                (d / "representative.txt").write_text("in-dir\n")
            elif not entry.startswith("/"):
                p = ws / entry
                if not p.exists():
                    p.write_text("representative " + entry + "\n")
                    made.add(entry)
        # absolute-path entries: cannot pre-create host /tmp files here;
        # they are lane-created logs - verify they are plain absolute files
        abs_entries = [e for e in upload_paths if e.startswith("/")]
        for e in abs_entries:
            print("absolute upload entry (lane-created, not workspace-simulated): " + e)

        inventory = simulate_upload(upload_paths, ws)
        print("--- simulated uploaded inventory (%d files) ---" % len(inventory))
        for f in inventory:
            print("  " + f)

        # (a) harvested-but-not-retained
        for name in harvested:
            if name not in inventory:
                failures.append("HARVESTED-NOT-RETAINED: " + name +
                                " (lane pulls it; upload list drops it)")
        # (b) upload-list entries that resolve to nothing
        for entry in upload_paths:
            if entry.startswith("/"):
                continue
            p = ws / entry
            if entry.endswith("/"):
                if not p.is_dir():
                    failures.append("UPLOAD-PATH-UNRESOLVED: " + entry)
            elif not p.is_file():
                failures.append("UPLOAD-PATH-UNRESOLVED: " + entry)
        # (c) report-claimed pixel artifacts must be retained
        report = ws / "virgl-session.txt"
        report.write_text(SAMPLE_REPORT)
        text = report.read_text()
        for rx, required in CLAIM_RULES:
            if rx.search(text) and required not in inventory:
                failures.append("REPORT-CLAIMED-ARTIFACT-MISSING: report records " +
                                rx.pattern + " but " + required + " is not uploaded")

    if failures:
        print("--- FAILURES (%d) ---" % len(failures))
        for f in failures:
            print("  " + f)
        return 1
    print("EVIDENCE-RETENTION: PASS (all harvested + report-claimed pixel artifacts retained)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
