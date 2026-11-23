"""停2 提前裁决包取证：源码锚点、链接、证据 SHA 与工作树。只读；拒绝覆盖结果。"""
from pathlib import Path
import datetime
import hashlib
import re
import subprocess
import urllib.parse

ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = Path(__file__).resolve().parent
PACKET = EVIDENCE / "stop2-review-packet.md"
OUT = EVIDENCE / "stop2-host-audit-result.txt"

# (标签, 相对路径, 1 基行号, 该行逐字内容) —— 行号漂移即失败，不改包内引用。
ANCHORS = [
    ("F1", "core/tools/src/main/java/io/javanatic/harness/tools/ToolsPlugin.java", 26,
     "        ApprovalService approval = scope.require(ApprovalService.KEY);"),
    ("F1", "core/tools/src/main/java/io/javanatic/harness/tools/ToolExecutorImpl.java", 36,
     "    private final ApprovalService approval;"),
    ("F1", "core/tools/src/main/java/io/javanatic/harness/tools/ToolExecutorImpl.java", 150,
     "                approval.require(request, signal);"),
    ("F1", "core/agent-loop/src/main/java/io/javanatic/harness/agentloop/AgentLoopPlugin.java", 93,
     "            scope.require(ToolExecutor.KEY),"),
    ("F1", "interaction/approval/src/main/java/io/javanatic/harness/interaction/approval/ApprovalAskPlugin.java", 30,
     "    public ApprovalAskPlugin(ApprovalPrompt prompt) {"),
    ("F1", "bundle/base/src/main/java/io/javanatic/harness/boot/AppBoot.java", 183,
     "    public static Booted bootReported(BootOptions options) throws IOException {"),
    ("F1", "bundle/base/src/main/java/io/javanatic/harness/boot/AppBoot.java", 197,
     "        loader.loadAll(runtime, enabled.stream().map(row -> discovered.get(row.plugin())).toList());"),
    ("F2", "core/agent-loop/src/main/java/io/javanatic/harness/agentloop/AbortController.java", 30,
     "        return new AbortSignalView();"),
    ("F2", "core/agent-loop/src/main/java/io/javanatic/harness/agentloop/AgentLoopImpl.java", 287,
     "        AbortSignal signal = abort.signal();"),
    ("F2", "core/agent-loop/src/main/java/io/javanatic/harness/agentloop/AgentLoopImpl.java", 396,
     "        AbortSignal signal = abort.signal();"),
    ("F2", "core/agent-loop/src/main/java/io/javanatic/harness/agentloop/AgentLoopImpl.java", 411,
     "            LlmCallConfig config = events.waterfall(AgentEvents.REQUEST, agentScope, this,"),
    ("F2", "core/agent-loop/src/main/java/io/javanatic/harness/agentloop/AgentLoopImpl.java", 466,
     "            List<LoggedEvent<ToolResultEvent>> results = executor.execute(calls, session, turn,"),
    ("F3", "delegation/local/src/main/java/io/javanatic/harness/delegation/local/LocalDelegationService.java", 75,
     "        if (parentSession.get() != null) {"),
    ("F3", "delegation/local/src/main/java/io/javanatic/harness/delegation/local/LocalDelegationService.java", 200,
     "        Thread.startVirtualThread(() -> {"),
    ("F3", "delegation/local/src/main/java/io/javanatic/harness/delegation/local/DelegationChildSetup.java", 16,
     " * 子代不得再委派，maxDepth=1 的结构性执法）；沙箱/审批档恒等继承（恒等也是显式"),
    ("F3", "delegation/local/src/main/java/io/javanatic/harness/delegation/local/LocalDelegationPlugin.java", 47,
     "        scope.events().on(AgentEvents.CREATED,"),
]

# 包内“没有注入口 / 不重解析”的否定断言。
ABSENT = [
    ("H1", "bundle/base/src/main/java/io/javanatic/harness/boot/AppBoot.java", "ApprovalPrompt"),
    ("F1", "core/tools/src/main/java/io/javanatic/harness/tools/ToolExecutorImpl.java", "ApprovalService.KEY"),
]

EXPECTED_WORKTREE = {
    ".agents/pits.md",
    ".gitattributes",
    "docs/plan/current-state.md",
    "docs/plan/iteration-38.md",
    "examples/headless/src/test/java/io/javanatic/harness/examples/headless/HeadlessOptionsTest.java",
    "examples/headless/src/test/java/io/javanatic/harness/examples/headless/HeadlessWebWriteOptionsTest.java",
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True)


def fields(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines() if "=" in line)


def anchor_checks(failures):
    lines = []
    for label, name, line, text in ANCHORS:
        path = ROOT / name
        if not path.exists():
            failures.append(f"missing source: {name}")
            lines.append(f"ANCHOR FAIL {label} {name}:{line} missing")
            continue
        rows = path.read_text().splitlines()
        actual = rows[line - 1].rstrip() if len(rows) >= line else "<EOF>"
        if actual == text:
            lines.append(f"ANCHOR ok {label} {name}:{line}")
        else:
            failures.append(f"anchor drift: {name}:{line}")
            lines.append(f"ANCHOR FAIL {label} {name}:{line} actual={actual!r}")
    for label, name, needle in ABSENT:
        found = needle in (ROOT / name).read_text()
        if found:
            failures.append(f"unexpected text: {name} contains {needle}")
        lines.append(f"ABSENT {'FAIL' if found else 'ok'} {label} {name} !{needle}")
    return lines


def evidence_checks(failures):
    lines = []
    red = fields(EVIDENCE / "stop2-cli-r4-red-result.txt")
    red_log = EVIDENCE / "stop2-cli-r4-red.log"
    red_match = digest(red_log) == red["LOG_SHA256"] if red_log.exists() else False
    if not (red.get("EXIT") == "1" and red.get("FRESH_TOTAL") == "[36, 7, 1, 0]"
            and red.get("MISSING") == "[]" and red_match):
        failures.append("cli-r4-red result/log mismatch")
    lines.append(f"CLI_RED EXIT={red.get('EXIT')} FRESH_TOTAL={red.get('FRESH_TOTAL')} "
                 f"MISSING={red.get('MISSING')} LOG_SHA256_MATCH={'yes' if red_match else 'no'}")
    val = fields(EVIDENCE / "stop2-host-validate-result.txt")
    val_log = EVIDENCE / "stop2-host-validate.log"
    val_match = digest(val_log) == val["LOG_SHA256"] if val_log.exists() else False
    if not (val.get("EXIT") == "0" and val_match):
        failures.append("validate result/log mismatch")
    lines.append(f"VALIDATE EXIT={val.get('EXIT')} LOG_SHA256_MATCH={'yes' if val_match else 'no'} "
                 f"SCOPE={val.get('SCOPE')}")
    return lines


def link_checks(failures):
    lines = []
    for doc in (PACKET, ROOT / "docs/plan/iteration-38.md", ROOT / "docs/plan/current-state.md"):
        for link in re.findall(r"\]\(([^)]+)\)", doc.read_text()):
            if link.startswith(("http:", "https:", "#", "mailto:")):
                continue
            target = urllib.parse.unquote(link.split("#", 1)[0])
            if not target:
                continue
            row = f"{doc.relative_to(ROOT)} -> {link}"
            resolved = (doc.parent / target).resolve()
            if resolved.name == OUT.name and resolved.parent == OUT.parent:
                lines.append("LINK self " + row)
            elif resolved.exists():
                lines.append("LINK ok " + row)
            else:
                failures.append("missing link: " + row)
                lines.append("LINK FAIL " + row)
    return lines


def worktree_checks(failures):
    lines = []
    for entry in git("status", "--porcelain").splitlines():
        path = entry[3:]
        lines.append("WORKTREE " + entry)
        if path not in EXPECTED_WORKTREE and not path.startswith("docs/plan/evidence/iteration-38/stop2-"):
            failures.append("unexpected worktree change: " + path)
    for rule in ("docs/plan/evidence/iteration-38/stop2-*.log -text !eol whitespace=-trailing-space,-blank-at-eof",
                 "docs/plan/evidence/iteration-38/stop2-*-result.txt -text !eol whitespace=-trailing-space,-blank-at-eof"):
        if rule not in (ROOT / ".gitattributes").read_text():
            failures.append("missing gitattributes rule: " + rule)
        lines.append("GITATTR " + ("ok" if rule in (ROOT / ".gitattributes").read_text() else "FAIL") + " " + rule)
    diff = subprocess.run(["git", "diff", "--check"], cwd=ROOT, capture_output=True, text=True)
    if diff.returncode:
        failures.append(diff.stdout + diff.stderr)
    lines.append(f"GIT_DIFF_CHECK_EXIT={diff.returncode}")
    for path in (EVIDENCE / "stop2-check.py", EVIDENCE / "stop2-host-audit.py", PACKET):
        data = path.read_bytes()
        if not data.endswith(b"\n") or data.endswith(b"\n\n"):
            failures.append("final newline: " + str(path.relative_to(ROOT)))
        if any(line.rstrip(b" \t") != line for line in data.splitlines()):
            failures.append("trailing whitespace: " + str(path.relative_to(ROOT)))
    return lines


def main():
    if OUT.exists():
        raise SystemExit(f"refuse overwrite: {OUT}")
    failures = []
    lines = [
        "COMMAND=python3 docs/plan/evidence/iteration-38/stop2-host-audit.py",
        "TIME=" + datetime.datetime.now().isoformat(),
        "HEAD=" + git("rev-parse", "HEAD").strip(),
        "PACKET_SHA256=" + digest(PACKET),
    ]
    anchor_lines = anchor_checks(failures)
    lines.append(f"ANCHORS_VERIFIED={sum(1 for row in anchor_lines if row.startswith('ANCHOR ok'))}/{len(ANCHORS)}")
    lines.extend(anchor_lines)
    lines.extend(evidence_checks(failures))
    link_lines = link_checks(failures)
    lines.append(f"LOCAL_LINKS={sum(1 for row in link_lines if row.startswith(('LINK ok', 'LINK self')))}")
    lines.extend(link_lines)
    lines.extend(worktree_checks(failures))
    lines.append("FAILURES=" + repr(failures))
    lines.append(f"EXIT={int(bool(failures))}")
    OUT.write_text("\n".join(lines) + "\n")
    print("\n".join(lines))
    raise SystemExit(1 if failures else 0)


main()
