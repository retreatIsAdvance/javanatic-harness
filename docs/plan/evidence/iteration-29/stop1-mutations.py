#!/usr/bin/env python3
"""One-at-a-time Definition mutations; restore exact source bytes in finally.
Run from repository root with JAVA_HOME and MVN supplied. No LS or network probe.
Outputs a new directory; never overwrites prior evidence.
"""
import hashlib
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
from datetime import datetime, timezone
import xml.etree.ElementTree as ET

ROOT = Path.cwd()
SRC = ROOT / "lsp/lsp/src/main/java/io/javanatic/harness/lsp"
TEST = ROOT / "lsp/lsp/src/test/java"
OUT = ROOT / "docs/plan/evidence/iteration-29/stop1-mutations-01"
MUTANTS = [
    ("M1-anchor", "LspObservation.java", "if (!before.equals(after))", "if (false)", "observedRequiresStableAnchorAndCompleteWindow"),
    ("M2-window", "LspObservation.java", "if (elapsedWait.compareTo(requiredWait) < 0)", "if (false)", "observedRequiresStableAnchorAndCompleteWindow"),
    ("M3-copy", "LspResult.java", "items = List.copyOf(items);", "items = Objects.requireNonNull(items);", "observedCopiesItemsAndRejectsNullEntries"),
    ("M4-hash", "LspValues.java", 'if (!value.matches("[0-9a-f]{64}"))', "if (false)", "hashesAndGenerationAreValidated"),
    ("M5-position", "LspPosition.java", "if (line < 0 || character < 0)", "if (false)", "positionRequiresNonnegativeUtf16Coordinates"),
    ("M6-budget", "LspQuery.java", "if (observationWindow.compareTo(timeout) > 0)", "if (false)", "windowMustFitInsidePositiveTotalBudget"),
    ("M7-missing", "LspObservation.java", "if (after.document().sha256().isEmpty())", "if (false)", "missingFileIsStaleButNeverSuccessfulObservation"),
    ("M8-quiet", "LspResult.java", "if (observation.evidence().origin() == LspEvidence.Origin.QUIET_WINDOW && !items.isEmpty())", "if (false)", "emptyPublicationKeepsVersionAndDiffersFromQuietWindow"),
]
if sys.argv[1:] == ["review"]:
    OUT = ROOT / "docs/plan/evidence/iteration-29/stop1-mutations-02"
    MUTANTS = [
        ("M9-stale-root", "LspResult.java", "if (!before.projectRoot().equals(after.projectRoot())\n                || !before.document().uri().equals(after.document().uri()))", "if (!before.document().uri().equals(after.document().uri()))", "staleRequiresChangeWithinSameQueryTarget"),
        ("M10-response-version", "LspEvidence.java", "origin != Origin.PUBLISHED_DIAGNOSTICS && serverVersion.isPresent()", "origin == Origin.QUIET_WINDOW && serverVersion.isPresent()", "publicationMetadataCannotInventVersionOrNegativeSequence"),
    ]
elif sys.argv[1:]:
    raise SystemExit("usage: stop1-mutations.py [review]")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def normalized(text):
    return "\n".join(line.rstrip() for line in re.sub(r"\x1b\[[0-9;]*m", "", text).splitlines()) + "\n"


def main():
    if not SRC.is_dir() or not (ROOT / "pom.xml").is_file():
        raise RuntimeError("run from repository root")
    command = [os.environ["MVN"], "-B", "-q", "-pl", "lsp/lsp", "-am", "test"]
    if not Path(os.environ["JAVA_HOME"]).is_dir():
        raise RuntimeError("JAVA_HOME missing")
    originals = {p: p.read_bytes() for p in SRC.glob("*.java")}
    tests = {p: p.read_bytes() for p in TEST.rglob("*.java")}
    OUT.mkdir(exist_ok=False)
    summary = ["it29 stop1 Definition mutations", "START=" + datetime.now(timezone.utc).isoformat(),
               "COMMAND=" + shlex.join(command), "JAVA_HOME=" + os.environ["JAVA_HOME"],
               "All Maven logs normalized for ANSI/trailing whitespace; original stop0 bytes untouched."]
    okay = True
    try:
        for name, filename, old, new, expected_test in MUTANTS:
            path = SRC / filename
            original = originals[path]
            text = original.decode()
            if text.count(old) != 1:
                raise RuntimeError("mutation target is not unique: " + name)
            if any(p.read_bytes() != data for p, data in tests.items()):
                raise RuntimeError("test inputs changed")
            started_ns = __import__("time").time_ns()
            path.write_text(text.replace(old, new))
            try:
                proc = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=180)
                log = "COMMAND=" + shlex.join(command) + "\n" + proc.stdout + proc.stderr + "\nEXIT=" + str(proc.returncode) + "\n"
                (OUT / (name + ".txt")).write_text(normalized(log))
                failures = []
                errors = []
                for xml in (ROOT / "lsp/lsp/target/surefire-reports").glob("TEST-*.xml"):
                    if xml.stat().st_mtime_ns < started_ns:
                        continue
                    for case in ET.parse(xml).getroot().iter("testcase"):
                        if case.find("failure") is not None:
                            failures.append(case.attrib["name"])
                        if case.find("error") is not None:
                            errors.append(case.attrib["name"])
                killed = proc.returncode != 0 and any(expected_test in f for f in failures) and not errors
                summary.append(f"{name}: EXIT={proc.returncode}; assertionFailures={failures}; errors={errors}; expected={expected_test}; KILLED={killed}")
                okay = okay and killed
            finally:
                path.write_bytes(original)
                restored = path.read_bytes() == original
                summary.append(f"{name}: restoredBytes={restored}; SHA256={digest(original)}")
            if not okay:
                break
    except (OSError, RuntimeError, subprocess.TimeoutExpired, ET.ParseError) as exc:
        okay = False
        summary.append("EXPERIMENT_ERROR=" + repr(exc))
    finally:
        for path, data in originals.items():
            if path.read_bytes() != data:
                path.write_bytes(data)
        unchanged = all(p.read_bytes() == data for p, data in originals.items())
        tests_unchanged = all(p.read_bytes() == data for p, data in tests.items())
        residual = [str(p.relative_to(ROOT)) for p in SRC.glob("*.java") if "if (false)" in p.read_text()]
        summary.extend(["ALL_SOURCE_BYTES_RESTORED=" + str(unchanged), "ALL_TEST_BYTES_UNCHANGED=" + str(tests_unchanged), "RESIDUAL_MUTANT_MARKERS=" + repr(residual)])
        okay = okay and unchanged and tests_unchanged and not residual
        summary.append("END=" + datetime.now(timezone.utc).isoformat())
        summary.append("EXIT=" + str(0 if okay else 1))
        (OUT / "summary.txt").write_text("\n".join(summary) + "\n")
    print("\n".join(summary))
    return 0 if okay else 1


if __name__ == "__main__":
    sys.exit(main())
