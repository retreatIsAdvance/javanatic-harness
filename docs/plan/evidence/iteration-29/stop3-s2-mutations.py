#!/usr/bin/env python3
"""it29 stop3-S2 mutations: verbatim template discipline, budget markers,
optional reader, document fence, dual-state declaration; restore exact source
bytes in finally. Run from repository root with JAVA_HOME and MVN supplied.
No LS or network probe beyond the jdtls fixture processes of the fence test.
Each mutant runs only its expected test (surefire method filter). Outputs a
new directory; never overwrites prior evidence.
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
LSP_TOOL_MAIN = ROOT / "lsp/tool/src/main/java/io/javanatic/harness/lsp/tool"
CORE_TOOLS_MAIN = ROOT / "core/tools/src/main/java/io/javanatic/harness/tools"
JDTLS_MAIN = ROOT / "lsp/jdtls/src/main/java/io/javanatic/harness/lsp/jdtls"
OUT = ROOT / "docs/plan/evidence/iteration-29/stop3-s2-mutations-01"
REPORTS = {
    "harness-lsp-tool": ROOT / "lsp/tool/target/surefire-reports",
    "harness-core-tools": ROOT / "core/tools/target/surefire-reports",
    "harness-lsp-jdtls": ROOT / "lsp/jdtls/target/surefire-reports",
}
# (name, selector, main-dir, filename, old, new, expected test method)
MUTANTS = [
    ("M1-banned-word-quiet-window", ":harness-lsp-tool", LSP_TOOL_MAIN, "LspToolOutput.java",
     'case QUIET_WINDOW -> "拉取成功（hash " + hash8',
     'case QUIET_WINDOW -> "项目无错误（hash " + hash8',
     "LspToolOutputTest#quietWindowTextIsVerbatim"),
    ("M2-banned-word-empty-batch", ":harness-lsp-tool", LSP_TOOL_MAIN, "LspToolOutput.java",
     '"拉取成功（hash " + hash8 + "）；LS 发布空诊断批次（0 条）。";',
     '"无诊断完成（hash " + hash8 + "）；LS 发布空诊断批次（0 条）。";',
     "LspToolOutputTest#publishedEmptyBatchNamesZeroAndNeverClaimsErrorFree"),
    ("M3-banned-word-empty-locations", ":harness-lsp-tool", LSP_TOOL_MAIN, "LspToolOutput.java",
     '"拉取成功（hash " + hash8 + "）；本次请求返回 0 个位置。";',
     '"拉取成功（hash " + hash8 + "）；符号不存在。";',
     "LspToolOutputTest#emptyLocationsNameZeroResultsNotAbsentSymbol"),
    ("M4-byte-cap-marker", ":harness-lsp-tool", LSP_TOOL_MAIN, "LspToolOutput.java",
     'BYTE_MARKER = " (output truncated)"',
     'BYTE_MARKER = " (truncated)"',
     "LspToolOutputTest#byteCapCutsAtUtf8BoundaryAndKeepsAnchorHeader"),
    ("M5-count-cap-marker", ":harness-lsp-tool", LSP_TOOL_MAIN, "LspToolOutput.java",
     'COUNT_MARKER = "…（截断）"',
     'COUNT_MARKER = "…"',
     "LspToolOutputTest#countCapKeepsAnchorHeaderAndAppendsMarker"),
    ("M6-optional-reader-always-empty", ":harness-core-tools", CORE_TOOLS_MAIN, "ToolArgs.java",
     "return value == null ? OptionalLong.empty() : OptionalLong.of(value.asLong());",
     "return OptionalLong.empty();",
     "ToolArgsTest#readOptionalLongEmptyWhenAbsentAndValueWhenPresent"),
    ("M7-fence-removed", ":harness-lsp-jdtls", JDTLS_MAIN, "JdtlsProvider.java",
     "        requireInsideProject(query.document());\n",
     "",
     "JdtlsProviderTest#documentOutsideProjectRootIsFencedWithUnsupported"),
    ("M8-requires-declaration-dropped", ":harness-lsp-tool", LSP_TOOL_MAIN, "LspToolPlugin.java",
     '        return Set.of("tools");',
     "        return Set.of();",
     "LspToolPluginTest#pluginShapeRequiresOnlyTools"),
]
if sys.argv[1:]:
    raise SystemExit("usage: stop3-s2-mutations.py")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def normalized(text):
    return "\n".join(line.rstrip() for line in re.sub(r"\x1b\[[0-9;]*m", "", text).splitlines()) + "\n"


def main():
    if not LSP_TOOL_MAIN.is_dir() or not (ROOT / "pom.xml").is_file():
        raise RuntimeError("run from repository root")
    if not Path(os.environ["JAVA_HOME"]).is_dir():
        raise RuntimeError("JAVA_HOME missing")
    tracked = [LSP_TOOL_MAIN / "LspToolOutput.java", LSP_TOOL_MAIN / "LspToolPlugin.java",
               CORE_TOOLS_MAIN / "ToolArgs.java", JDTLS_MAIN / "JdtlsProvider.java"]
    originals = {p: p.read_bytes() for p in tracked}
    test_trees = [ROOT / "lsp/tool/src/test", ROOT / "core/tools/src/test",
                  ROOT / "lsp/jdtls/src/test"]
    tests = {p: p.read_bytes() for tree in test_trees for p in tree.rglob("*.java")}
    OUT.mkdir(exist_ok=False)
    summary = ["it29 stop3-S2 Consumer mutations", "START=" + datetime.now(timezone.utc).isoformat(),
               "JAVA_HOME=" + os.environ["JAVA_HOME"],
               "Each mutant runs only its expected test (surefire method filter)."]
    okay = True
    try:
        for name, selector, main_dir, filename, old, new, expected in MUTANTS:
            path = main_dir / filename
            original = originals[path]
            text = original.decode()
            if text.count(old) != 1:
                raise RuntimeError("mutation target is not unique: " + name)
            if any(p.read_bytes() != data for p, data in tests.items()):
                raise RuntimeError("test inputs changed")
            started_ns = __import__("time").time_ns()
            command = [os.environ["MVN"], "-B", "-q", "-pl", selector, "-am", "test",
                       "-Dsurefire.failIfNoSpecifiedTests=false", "-Dtest=" + expected]
            path.write_text(text.replace(old, new))
            try:
                proc = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=300)
                log = "COMMAND=" + shlex.join(command) + "\n" + proc.stdout + proc.stderr \
                    + "\nEXIT=" + str(proc.returncode) + "\n"
                (OUT / (name + ".txt")).write_text(normalized(log))
                failures = []
                errors = []
                for xml in REPORTS[selector.split(":")[1]].glob("TEST-*.xml"):
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
        banned = []
        for pattern in ["项目无错误", "无诊断完成", "符号不存在"]:
            for path in tracked:
                if pattern in path.read_text():
                    banned.append(str(path.relative_to(ROOT)) + ":" + pattern)
        summary.extend(["ALL_SOURCE_BYTES_RESTORED=" + str(unchanged),
                        "ALL_TEST_BYTES_UNCHANGED=" + str(tests_unchanged),
                        "RESIDUAL_BANNED_WORDS=" + repr(banned)])
        okay = okay and unchanged and tests_unchanged and not banned
        summary.append("END=" + datetime.now(timezone.utc).isoformat())
        summary.append("EXIT=" + str(0 if okay else 1))
        (OUT / "summary.txt").write_text("\n".join(summary) + "\n")
    print("\n".join(summary))
    return 0 if okay else 1


if __name__ == "__main__":
    sys.exit(main())
