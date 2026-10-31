#!/usr/bin/env python3
"""it29 stop3-S1 JdtlsPlugin mutations; restore exact source bytes in finally.
Run from repository root with JAVA_HOME and MVN supplied. No LS or network probe.
Each mutant runs only its expected test (surefire method filter). Outputs a new
directory; never overwrites prior evidence.
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
MAIN = ROOT / "lsp/jdtls/src/main/java/io/javanatic/harness/lsp/jdtls"
OUT = ROOT / "docs/plan/evidence/iteration-29/stop3-s1-mutations-01"
MUTANTS = [
    ("M1-whitelist", "JdtlsPluginConfig.java", "if (!KEYS.contains(key)) {", "if (false) {",
     "JdtlsPluginConfigTest#unknownKeyFailsLoud"),
    ("M2-home-default", "JdtlsPluginConfig.java", '"jh-lsp-" + suffix', '"jh-fixed-" + suffix',
     "JdtlsPluginConfigTest#defaultsProbeJavaHomeAndDeriveTempDirs"),
    ("M3-index-created", "JdtlsPluginConfig.java", "Files.createDirectories(indexDir);",
     "Files.createDirectories(homeDir);", "JdtlsPluginConfigTest#toLaunchCreatesHomeAndIndexAndBuildsValidArchive"),
    ("M4-on-close", "JdtlsPlugin.java", "        scope.onClose(service::close);\n", "",
     "JdtlsPluginTest#applyProvidesServiceAndScopeCloseClosesProvider"),
    ("M5-provide", "JdtlsPlugin.java", "        scope.provide(LspService.KEY, service);\n", "",
     "JdtlsPluginTest#applyProvidesServiceAndScopeCloseClosesProvider"),
]
if sys.argv[1:]:
    raise SystemExit("usage: stop3-s1-mutations.py")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def normalized(text):
    return "\n".join(line.rstrip() for line in re.sub(r"\x1b\[[0-9;]*m", "", text).splitlines()) + "\n"


def main():
    if not MAIN.is_dir() or not (ROOT / "pom.xml").is_file():
        raise RuntimeError("run from repository root")
    base = [os.environ["MVN"], "-B", "-q", "-pl", "lsp/jdtls", "-am", "test",
            "-Dsurefire.failIfNoSpecifiedTests=false"]
    if not Path(os.environ["JAVA_HOME"]).is_dir():
        raise RuntimeError("JAVA_HOME missing")
    tracked = [MAIN / "JdtlsPluginConfig.java", MAIN / "JdtlsPlugin.java"]
    originals = {p: p.read_bytes() for p in tracked}
    tests = {p: p.read_bytes() for p in (ROOT / "lsp/jdtls/src/test").rglob("*.java")}
    OUT.mkdir(exist_ok=False)
    summary = ["it29 stop3-S1 JdtlsPlugin mutations", "START=" + datetime.now(timezone.utc).isoformat(),
               "JAVA_HOME=" + os.environ["JAVA_HOME"],
               "Each mutant runs only its expected test (surefire method filter)."]
    okay = True
    try:
        for name, filename, old, new, expected in MUTANTS:
            path = MAIN / filename
            original = originals[path]
            text = original.decode()
            if text.count(old) != 1:
                raise RuntimeError("mutation target is not unique: " + name)
            if any(p.read_bytes() != data for p, data in tests.items()):
                raise RuntimeError("test inputs changed")
            started_ns = __import__("time").time_ns()
            command = base + ["-Dtest=" + expected]
            path.write_text(text.replace(old, new))
            try:
                proc = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=240)
                log = "COMMAND=" + shlex.join(command) + "\n" + proc.stdout + proc.stderr \
                    + "\nEXIT=" + str(proc.returncode) + "\n"
                (OUT / (name + ".txt")).write_text(normalized(log))
                failures = []
                errors = []
                for xml in (ROOT / "lsp/jdtls/target/surefire-reports").glob("TEST-*.xml"):
                    if xml.stat().st_mtime_ns < started_ns:
                        continue
                    for case in ET.parse(xml).getroot().iter("testcase"):
                        if case.find("failure") is not None:
                            failures.append(case.attrib["name"])
                        if case.find("error") is not None:
                            errors.append(case.attrib["name"])
                method = expected.split("#")[1]
                killed = proc.returncode != 0 and any(method in f for f in failures + errors)
                summary.append(f"{name}: EXIT={proc.returncode}; failures={failures}; errors={errors};"
                               f" expected={expected}; KILLED={killed}")
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
        residual = [str(p.relative_to(ROOT)) for p in tracked
                    if "if (false)" in p.read_text() or '"jh-fixed-"' in p.read_text()]
        summary.extend(["ALL_SOURCE_BYTES_RESTORED=" + str(unchanged),
                        "ALL_TEST_BYTES_UNCHANGED=" + str(tests_unchanged),
                        "RESIDUAL_MUTANT_MARKERS=" + repr(residual)])
        okay = okay and unchanged and tests_unchanged and not residual
        summary.append("END=" + datetime.now(timezone.utc).isoformat())
        summary.append("EXIT=" + str(0 if okay else 1))
        (OUT / "summary.txt").write_text("\n".join(summary) + "\n")
    print("\n".join(summary))
    return 0 if okay else 1


if __name__ == "__main__":
    sys.exit(main())
