#!/usr/bin/env python3
"""停 2 终包审计：锚点 / 否定断言 / 包内链接 / 证据存在性 / 源文件 SHA / 冻结规则。

只读检查；任何一项失败 → 打印 FAIL、不写结果文件、exit 1（便于修复后重跑）。
成功 → 写 stop2-final-audit-result.txt（已存在则拒绝覆盖，FORCE=1 可覆盖）。
"""
import hashlib
import os
import re
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(BASE, "..", "..", "..", ".."))
PACKET = os.path.join(BASE, "stop2-review-packet.md")
RESULT = os.path.join(BASE, "stop2-final-audit-result.txt")

CHAT = "examples/headless/src/main/java/io/javanatic/harness/examples/headless/WebChatControl.java"
HUB = "examples/headless/src/main/java/io/javanatic/harness/examples/headless/WebApprovalHub.java"
APPBOOT = "bundle/base/src/main/java/io/javanatic/harness/boot/AppBoot.java"
UI = "workbench/rpc/src/main/resources/io/javanatic/harness/workbench/rpc/ui.html"

EXPECTED_SHA = {
    CHAT: "74c81c3141782d165d94bdac7b27caf541b759290c2b47155d9b37a8bdf4219d",
    HUB: "3482399592481625d45874e00f5f9b130c5d3013cdb59ec37a8d39de3e6da2e4",
}
EXPECTED_HEAD = "27b2d88"
UI_LINES = 762
UI_HEAD_LINES = 441

failures = []
passes = []


def check(name, ok, detail=""):
    if ok:
        passes.append(f"PASS {name}" + (f" — {detail}" if detail else ""))
    else:
        failures.append(f"FAIL {name}" + (f" — {detail}" if detail else ""))


def read(rel):
    with open(os.path.join(REPO, rel), encoding="utf-8") as handle:
        return handle.read()


def sha256(rel):
    digest = hashlib.sha256()
    with open(os.path.join(REPO, rel), "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def line_of(text, needle):
    idx = text.find(needle)
    return text.count("\n", 0, idx) + 1 if idx >= 0 else -1


def git(*args):
    return subprocess.run(["git", "-C", REPO, *args], capture_output=True, text=True, check=True).stdout


# A. 基线
head = git("rev-parse", "--short", "HEAD").strip()
check("A1 基线 HEAD", head.startswith(EXPECTED_HEAD), f"HEAD={head}")
porcelain = git("status", "--porcelain").strip().splitlines()
check("A2 工作树非空（停 2 未提交面存在）", len(porcelain) > 0, f"{len(porcelain)} 条变更")

# B. 源码锚点
chat = read(CHAT)
hub = read(HUB)
appboot = read(APPBOOT)
ui = read(UI)

check("B1 租约挂 REQUEST", "onWaterfall(AgentEvents.REQUEST" in chat,
      f":{line_of(chat, 'onWaterfall(AgentEvents.REQUEST')}")
start = chat.find("private void finishRound(")
end = chat.find("private void startWatcher(")
body = chat[start:end]
guard = "if (st.gen != gen || st.retired || closing) {"
close_signal = "signal = st.signal;"
check("B2 finishRound 代守卫先于关信号",
      body.find(guard) >= 0 and body.find(close_signal) > body.find(guard),
      f"guard 出现 {body.count(guard)} 次，关信号在 guard 之后")
check("B3 退役标记复位", "st.retiring = false;" in chat,
      f":{line_of(chat, 'st.retiring = false;')}")
check("B4 注入调用点唯一", chat.count("bootReported(boot, injectPrompt ? hub.prompt() : null)") == 1,
      f":{line_of(chat, 'bootReported(boot, injectPrompt')}")
check("B5 形状漂移 fail-closed 留痕",
      "REQUEST shape mismatch; no approval lease for this round" in chat)
check("B6 feed 仅为真实租约建",
      "int feedCount()" in hub and "只为真实租约持有者建 feed" in hub,
      f"feedCount:{line_of(hub, 'int feedCount()')}")
check("B7 AppBoot 重载与注入前置",
      "bootReported(BootOptions options, ApprovalPrompt prompt)" in appboot
      and "static Map<String, Plugin> injectApprovalPrompt(" in appboot,
      f"重载:{line_of(appboot, 'bootReported(BootOptions options, ApprovalPrompt prompt)')} "
      f"定义:{line_of(appboot, 'static Map<String, Plugin> injectApprovalPrompt(')}")
ui_lines = [i + 1 for i, text in enumerate(ui.split("\n"))]
check("B8 UI 写面哨兵", "const WEB_WRITE = __JH_WEB_WRITE__;" in ui,
      f":{line_of(ui, 'const WEB_WRITE = __JH_WEB_WRITE__;')}")
ime_lines = [i + 1 for i, text in enumerate(ui.split("\n"))
             if "event.isComposing || event.keyCode === 229" in text]
check("B9 IME 双守卫逐字", len(ime_lines) == 2, f"行 {ime_lines}")
check("B10 UI 零 innerHTML", "innerHTML" not in ui)

work_lines = subprocess.run(["wc", "-l", os.path.join(REPO, UI)], capture_output=True, text=True,
                            check=True).stdout.split()[0]
head_ui = subprocess.run(["git", "-C", REPO, "show", f"HEAD:{UI}"], capture_output=True, text=True,
                         check=True).stdout
head_lines = head_ui.count("\n")
check("B11 UI 行数", int(work_lines) == UI_LINES and head_lines == UI_HEAD_LINES,
      f"工作树 {work_lines} / HEAD {head_lines}（无尾换行）")

# C. H1 否定断言：rpc 不得依赖 interaction-approval
rpc_module = read("workbench/rpc/src/main/java/module-info.java")
rpc_pom = read("workbench/rpc/pom.xml")
check("C1 rpc module-info 无 interaction.approval",
      "interaction.approval" not in rpc_module and "interaction-approval" not in rpc_module)
check("C2 rpc pom 无 interaction-approval",
      "interaction-approval" not in rpc_pom)

# D. 源文件 SHA 与突变总账一致
summary = read("docs/plan/evidence/iteration-38/stop2-mut-summary2-result.txt")
for rel, expected in EXPECTED_SHA.items():
    actual = sha256(rel)
    check(f"D {os.path.basename(rel)} SHA", actual == expected and expected[:8] in summary,
          f"{actual[:12]}…")

# E. 包内链接全部可解析
packet = read("docs/plan/evidence/iteration-38/stop2-review-packet.md")
links = re.findall(r"\]\(([^)#]+)\)", packet)
missing = []
for link in links:
    if link.startswith("http"):
        continue
    target = os.path.normpath(os.path.join(BASE, link))
    if not os.path.isfile(target):
        missing.append(link)
check("E1 包内链接可解析", not missing, f"{len(links)} 条链接，缺失 {missing}" if missing else f"{len(links)} 条链接")

# F. 关键证据非空
required = [
    "stop2-cli-r4-final-result.txt", "stop2-final-focus2-result.txt",
    "stop2-full-package2-result.txt", "stop2-final2-validate-result.txt",
    "stop2-exit2-final-result.txt", "stop2-banner-final-result.txt",
    "stop2-ui-lines-result.txt", "stop2-h1-boundary-result.txt",
    "stop2-mut-summary2-result.txt", "stop2-mut3-gen-guard-red.log",
]
empty = [name for name in required if not os.path.getsize(os.path.join(BASE, name))]
check("F1 关键证据非空", not empty, f"空文件 {empty}" if empty else f"{len(required)} 份")

# G. 冻结规则覆盖新证据
attrs = read(".gitattributes")
frozen = [name for name in os.listdir(BASE)
          if (name.startswith("stop2-") and name.endswith(".log"))
          or (name.startswith("stop2-") and name.endswith("-result.txt"))]
check("G1 冻结规则覆盖证据",
      "docs/plan/evidence/iteration-38/stop2-*.log" in attrs
      and "docs/plan/evidence/iteration-38/stop2-*-result.txt" in attrs,
      f"{len(frozen)} 份 log/result 匹配规则")

if failures:
    print("STOP2 FINAL AUDIT: FAIL")
    for line in passes + failures:
        print(line)
    sys.exit(1)

if os.path.exists(RESULT) and os.environ.get("FORCE") != "1":
    print(f"refuse to overwrite {RESULT} (FORCE=1 to override)", file=sys.stderr)
    sys.exit(2)

stamp = time.strftime("%Y-%m-%dT%H:%M:%S%z")
with open(RESULT, "w", encoding="utf-8") as out:
    out.write(f"停 2 终包审计 — {stamp}\n")
    out.write("SCOPE=只读：源码锚点 / H1 否定断言 / 包内链接 / 证据存在性 / 源 SHA / 冻结规则\n")
    out.write(f"BASE={head}  PORCELAIN={len(porcelain)}  UI={work_lines}/{head_lines}\n")
    for line in passes:
        out.write(line + "\n")
    out.write("RESULT=PASS\n")
print("\n".join(passes))
print("RESULT=PASS")
