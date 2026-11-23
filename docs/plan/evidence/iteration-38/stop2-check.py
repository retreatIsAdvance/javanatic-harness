"""停 2 独立运行证据：准确类名、原始日志、EXIT、同窗 XML；拒绝覆盖。"""
from pathlib import Path
import datetime
import hashlib
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = Path(__file__).resolve().parent
phase, module, tests, *extra = sys.argv[1:]
log = EVIDENCE / f"stop2-{phase}.log"
report = EVIDENCE / f"stop2-{phase}-result.txt"
if log.exists() or report.exists():
    raise SystemExit(f"refuse overwrite: {log}")
expected = {}
if tests != "-":
    for name in tests.split(","):
        files = list(ROOT.glob(f"**/src/test/java/**/{name}.java"))
        if len(files) != 1:
            raise SystemExit(f"test source count {len(files)}: {name}")
        source = files[0]
        parts = source.relative_to(ROOT).parts
        base = ROOT.joinpath(*parts[:parts.index("src")])
        expected[name] = base / "target/surefire-reports" / ("TEST-" + ".".join(parts[parts.index("java") + 1:]).removesuffix(".java") + ".xml")
cmd = ["/Users/abel_yang/Documents/apache-maven-3.10.0/bin/mvn", "-B", "-q"]
if module != "-":
    cmd += ["-pl", module, "-am"]
if tests != "-":
    cmd += [f"-Dtest={tests}", "-Dsurefire.failIfNoSpecifiedTests=false"]
cmd += extra or ["test"]
env = os.environ.copy()
env["JAVA_HOME"] = subprocess.check_output(["/usr/libexec/java_home", "-v", "25"], text=True).strip()
start = time.time()
with log.open("xb") as output:
    result = subprocess.run(cmd, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
end = time.time()
lines = [f"cwd={ROOT}", "JAVA_HOME=$(/usr/libexec/java_home -v 25)", "COMMAND=" + " ".join(cmd), f"START={datetime.datetime.fromtimestamp(start).isoformat()}", f"END={datetime.datetime.fromtimestamp(end).isoformat()}", f"EXIT={result.returncode}", "XML_COLUMNS=tests failures errors skipped"]
total = [0, 0, 0, 0]
missing = []
if tests == "-":
    expected = {str(p.relative_to(ROOT)): p for p in ROOT.glob("**/target/surefire-reports/TEST-*.xml") if start <= p.stat().st_mtime <= end}
for name, xml in expected.items():
    if not xml.exists() or not start <= xml.stat().st_mtime <= end:
        missing.append(name)
        lines.append(f"NO_FRESH_XML={name}")
        continue
    suite = ET.parse(xml).getroot()
    counts = [int(suite.get(k, 0)) for k in ("tests", "failures", "errors", "skipped")]
    total = [a + b for a, b in zip(total, counts)]
    lines.append(f"{name} {counts} mtime={datetime.datetime.fromtimestamp(xml.stat().st_mtime).isoformat()}")
    for case in suite.findall("testcase"):
        for tag in ("failure", "error"):
            for failure in case.findall(tag):
                lines.append(f"{tag.upper()}={case.get('name')} {failure.get('message')}")
lines += [f"FRESH_TOTAL={total}", f"MISSING={missing}", f"LOG_SHA256={hashlib.sha256(log.read_bytes()).hexdigest()}"]
report.write_text("\n".join(lines) + "\n")
print("\n".join(lines))
sys.exit(result.returncode or (1 if missing else 0))
