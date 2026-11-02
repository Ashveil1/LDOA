#!/usr/bin/env python3
"""Local retention-list coverage check for the input-harness lane.

SCOPE (honest): this is a LOCAL COVERAGE check of the upload-artifact
path list against the lane's harvested/produced diagnostic artifacts,
using a simulation of upload-artifact v4 path semantics (literal file /
trailing-slash directory). It does NOT run upload-artifact, does NOT
run the device pull, and does NOT validate artifact content. Absolute
/tmp entries (lane-created logs) are declared but NOT simulated - this
check says nothing about them. Header-gated device pull is not
end-to-end delivery; this check exists because run 41371167693 pulled
and header-validated virgl-session-xmap.ppm and then discarded it with
the runner: the upload path list predated the probe.

Chain:
  1. ACTUAL parse of the upload-artifact path list from
     .github/workflows/input-harness.yml (no hardcoded copy).
  2. ACTUAL parse of the lane's harvested artifacts from
     ci/emulator-lane.sh (pull_or_absent targets, incl. for-loop lists).
  3. Representative production files in a simulated workspace, named
     ONLY from the production side (harvest list + claim-rule artifacts
     + the PRODUCED_EXTRA list below) - NEVER from the upload entries,
     so the unresolved-entry check is a real check, not a tautology.
  4. Simulated path semantics produce the would-upload inventory.
  5. Checks (any failure exits 1):
     a. every harvested artifact is upload-retained;
     b. every relative upload entry resolves in the simulated workspace
        (catches path-list typos/renames);
     c. report-claimed pixel artifacts: window-pixels SAMPLED requires
        virgl-session-xmap.ppm retained; guest-xcontent CAPTURED
        requires virgl-session-xcontent.img (.partial optional).

--negative-selftest: re-runs the pipeline with the xmap.ppm upload
entry renamed and REQUIRES the mutated run to fail; proves the check
can actually catch losing the pixel artifact.

Usage: check_evidence_upload_paths.py <repo-root> [--negative-selftest]
"""
import re
import sys
import tempfile
from pathlib import Path

# Claim -> required uploaded artifact. Provenance: ci/emulator-lane.sh
# header-gate blocks pair exactly these verdicts with these files.
CLAIM_RULES = [
    (re.compile(r"VERDICT window-pixels: SAMPLED"), "virgl-session-xmap.ppm"),
    (re.compile(r"VERDICT overlay-pixels: SAMPLED"), "virgl-session-xov.ppm"),
    (re.compile(r"VERDICT guest-xcontent: CAPTURED"), "virgl-session-xcontent.img"),
]

# Workspace-root diagnostic files the lane produces OUTSIDE pull_or_absent
# (provenance: ci/emulator-lane.sh - bounded logcat capture redirect and
# the xcontrol root-dump step; screenshots/ is the Shot pipeline dir).
PRODUCED_EXTRA = ["logcat-run.txt", "virgl-session-root.xwd"]
PRODUCED_DIRS = ["screenshots"]

SAMPLE_REPORT = """\
VERDICT window-pixels: SAMPLED (5 of 5 patches; fixture sample)
VERDICT overlay-pixels: SAMPLED (5 of 5 patches; fixture sample)
VERDICT guest-xcontent: CAPTURED tool=xwd (fixture sample)
"""


def parse_upload_paths(workflow: str) -> list[str]:
    lines = Path(workflow).read_text().splitlines()
    i = 0
    while i < len(lines):
        if "uses: actions/upload-artifact@" in lines[i]:
            j = i + 1
            while j < len(lines) and not re.match(r"^\s+path:\s*\|", lines[j]):
                if j > i + 40:
                    break
                j += 1
            if j >= len(lines) or not re.match(r"^\s+path:\s*\|", lines[j]):
                raise SystemExit("upload-artifact step has no `path: |` block")
            indent = len(lines[j]) - len(lines[j].lstrip())
            k = j + 1
            paths: list[str] = []
            while k < len(lines):
                ln = lines[k]
                if ln.strip() == "":
                    k += 1
                    continue
                if len(ln) - len(ln.lstrip()) <= indent:
                    break
                paths.append(ln.strip())
                k += 1
            return paths
        i += 1
    raise SystemExit("no upload-artifact step found in workflow")


def parse_harvested(lane: str) -> list[str]:
    text = Path(lane).read_text()
    found: set[str] = set()
    for m in re.finditer(r"for f in ([^;]+);\s*do\s*\n\s*pull_or_absent \"\$f\"", text):
        found.update(m.group(1).split())
    for m in re.finditer(r"pull_or_absent ([A-Za-z0-9._/-]+) ", text):
        if m.group(1) != "$f":
            found.add(m.group(1))
    return sorted(found)


def simulate_upload(paths: list[str], workspace: Path) -> list[str]:
    inventory: list[str] = []
    for entry in paths:
        if entry.startswith("/"):
            # absolute path (lane-created /tmp logs): declared, NOT
            # simulated - never consult the real host filesystem.
            continue
        p = workspace / entry
        if entry.endswith("/"):
            if p.is_dir():
                inventory.extend(str(f.relative_to(workspace))
                                 for f in sorted(p.rglob("*")) if f.is_file())
        elif p.is_file():
            inventory.append(entry)
    return sorted(inventory)


def run_pipeline(root: Path, upload_paths: list[str], label: str) -> list[str]:
    harvested = parse_harvested(root / "ci/emulator-lane.sh")
    print("[%s] upload-list entries: %d, lane-harvested artifacts: %d"
          % (label, len(upload_paths), len(harvested)))
    failures: list[str] = []
    with tempfile.TemporaryDirectory() as td:
        ws = Path(td)
        # Representative production files: production-side names ONLY.
        for name in harvested + [r[1] for r in CLAIM_RULES] + PRODUCED_EXTRA:
            (ws / name).write_text("representative " + name + "\n")
        for d in PRODUCED_DIRS:
            (ws / d).mkdir(parents=True, exist_ok=True)
            (ws / d / "representative.txt").write_text("in-dir\n")

        inventory = simulate_upload(upload_paths, ws)
        print("[%s] simulated uploaded inventory (%d files):" % (label, len(inventory)))
        for f in inventory:
            print("  " + f)
        abs_entries = [e for e in upload_paths if e.startswith("/")]
        print("[%s] absolute entries declared, NOT simulated: %d" % (label, len(abs_entries)))

        for name in harvested:
            if name not in inventory:
                failures.append("HARVESTED-NOT-RETAINED: " + name +
                                " (lane pulls it; upload list drops it)")
        for entry in upload_paths:
            if entry.startswith("/"):
                continue
            p = ws / entry
            if entry.endswith("/"):
                if not p.is_dir():
                    failures.append("UPLOAD-PATH-UNRESOLVED: " + entry)
            elif not p.is_file():
                failures.append("UPLOAD-PATH-UNRESOLVED: " + entry)
        report = ws / "virgl-session.txt"
        report.write_text(SAMPLE_REPORT)
        text = report.read_text()
        for rx, required in CLAIM_RULES:
            if rx.search(text) and required not in inventory:
                failures.append("REPORT-CLAIMED-ARTIFACT-MISSING: report records " +
                                rx.pattern + " but " + required + " is not uploaded")
    return failures


def main() -> int:
    root = Path(sys.argv[1])
    upload_paths = parse_upload_paths(root / ".github/workflows/input-harness.yml")
    failures = run_pipeline(root, upload_paths, "actual")
    if failures:
        print("--- FAILURES (%d) ---" % len(failures))
        for f in failures:
            print("  " + f)
        return 1
    print("EVIDENCE-RETENTION: PASS (all harvested + report-claimed pixel artifacts retained)")

    if "--negative-selftest" in sys.argv:
        mutated = [e.replace("virgl-session-xmap.ppm", "virgl-session-xmap.ppm.renamed")
                          .replace("virgl-session-xov.ppm", "virgl-session-xov.ppm.renamed")
                   for e in upload_paths]
        if mutated == upload_paths:
            print("NEGATIVE-SELFTEST: ERROR (pixel entries not found to mutate)")
            return 1
        mfail = run_pipeline(root, mutated, "mutated")
        if not mfail:
            print("NEGATIVE-SELFTEST: FAIL (renaming the pixel upload entries was NOT caught)")
            return 1
        print("NEGATIVE-SELFTEST: PASS (renamed pixel entries produced %d expected failure(s))"
              % len(mfail))
    return 0


if __name__ == "__main__":
    sys.exit(main())
