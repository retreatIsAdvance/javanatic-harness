#!/usr/bin/env python3
"""有限命令编排，不代替人工审查；不解析旧 surefire XML、不接 hooks、不跑发布腿。

docs 拒绝变化范围内非 .md/.txt 文件（含暂存与未跟踪文件），不为源码改动背书。
"""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parent.parent
LINK = re.compile(r"\[[^\]\n]*\]\((<[^>\n]+>|[^()\s]+)\)")


class VerificationFailed(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def arguments():
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("layer", choices=("docs", "focused", "full", "consumer"))
    parser.add_argument("--log-dir", required=True, help="全新目录，父目录必须已存在")
    parser.add_argument("--base", default="HEAD", help="变化范围的 Git tree-ish，缺省 HEAD")
    parser.add_argument("--module", help="focused 专用，单个仓库内模块目录")
    args = parser.parse_args()
    if args.layer == "focused":
        value = args.module or ""
        if (not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_./ -]*", value)
                or ".." in Path(value).parts
                or not (ROOT / value).resolve().is_relative_to(ROOT)
                or not (ROOT / value / "pom.xml").is_file()):
            parser.error("focused 需要仓库内含 pom.xml 的单个 --module 目录")
    elif args.module is not None:
        parser.error("--module 仅用于 focused")
    return args


class Evidence:
    def __init__(self, args):
        self.directory = Path(args.log_dir).absolute()
        self.directory.mkdir()  # 不创建父目录，不覆盖既有证据（包括空目录 / 符号链接）。
        self.directory = self.directory.resolve()
        self.state = {"root": str(ROOT), "layer": args.layer, "base": args.base,
                      "module": args.module, "started": self.now(), "exit": None,
                      "steps": []}
        self.save()

    @staticmethod
    def now():
        return datetime.now(timezone.utc).isoformat()

    def save(self):
        (self.directory / "summary.txt").write_text(
            json.dumps(self.state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def run(self, label, command, capture=False, overrides=None):
        """不使用 shell / tee；日志先打开，子进程返回码与输出来自同一次执行。"""
        step = {"label": label, "command": command, "exit": None}
        self.state["steps"].append(step)
        path = self.directory / f"{len(self.state['steps']):02d}-{label}.txt"
        step["log"] = path.name
        self.save()
        print(f"RUN {label}: {path}", flush=True)
        with path.open("xb") as log:
            log.write(("COMMAND=" + shlex.join(command) + "\n").encode())
            if overrides:
                # 仅记录本次显式生成的 consumer 目录与 Maven 路径，不转储宿主环境。
                log.write(("OVERRIDES=" + json.dumps(overrides) + "\n").encode())
            log.flush()
            try:
                result = subprocess.run(command, cwd=ROOT,
                                        env=dict(os.environ, **(overrides or {})),
                                        stdout=subprocess.PIPE if capture else log,
                                        stderr=subprocess.STDOUT)
                code = result.returncode if result.returncode >= 0 else 128 - result.returncode
                step["exit"] = code
                output = result.stdout if capture else b""
                if capture:
                    # Git -z 结果保留在返回值；日志转义，避免 NUL 干扰审查工具。
                    log.write(repr(output).encode() + b"\n")
            except FileNotFoundError as error:
                code, output = 127, b""
                log.write((str(error) + "\n").encode())
            except PermissionError as error:
                code, output = 126, b""
                log.write((str(error) + "\n").encode())
            step["exit"] = code
            log.write(f"\nEXIT={code}\n".encode())
        self.save()
        if code:
            raise VerificationFailed(code, f"{label} 失败，日志：{path}")
        return output


def changed_files(evidence, base):
    git = ["git"]
    actual_root = evidence.run("git-root", git + ["rev-parse", "--show-toplevel"], True)
    if Path(os.fsdecode(actual_root).strip()).resolve() != ROOT:
        raise VerificationFailed(2, "脚本必须位于待验证仓库的 integration 目录")
    tree = evidence.run("git-base", git + ["rev-parse", "--verify", "--end-of-options",
                                          base + "^{tree}"], True).decode().strip()
    evidence.state["base_tree"] = tree
    head = evidence.run("git-head", git + ["rev-parse", "--revs-only", "HEAD"], True)
    evidence.state["head"] = head.decode().strip() or "UNBORN"
    paths = set()
    for label, flags in (("worktree", []), ("index", ["--cached"])):
        evidence.run(f"diff-check-{label}", git + ["diff", *flags, "--check", tree, "--"])
        data = evidence.run(f"paths-{label}",
                            git + ["diff", *flags, "--name-only", "--no-renames", "-z", tree, "--"], True)
        paths.update(os.fsdecode(path) for path in data.split(b"\0") if path)
    data = evidence.run("paths-untracked", git + ["ls-files", "--others", "--exclude-standard", "-z"], True)
    paths.update(os.fsdecode(path) for path in data.split(b"\0") if path)
    # 排除日志的目录项，不排除指向日志的源码符号链接。
    paths = sorted(path for path in paths
                   if not (ROOT / path).is_relative_to(evidence.directory))
    evidence.state["changed_files"] = paths
    evidence.save()
    return paths


def document_checks(evidence, paths, docs_only):
    """只检查简单行内相对链接的文件存在性；完整 Markdown 语义与锚点交给人工。"""
    errors = []
    checked = 0
    with (evidence.directory / "documents.txt").open("x", encoding="utf-8") as log:
        log.write("CHECK=变化范围与简单行内相对链接（进程内检查）\n")
        log.write("人工核对：语义、代码路径引用、锚点、引用式链接、HTML、复杂/多行 Markdown。\n")
        log.write("范围：基线到工作树/暂存区的路径并集 + 非忽略未跟踪文件；内容读取工作树。\n")
        for name in paths:
            log.write(f"PATH={name!r}\n")
            path = ROOT / name
            if docs_only and path.suffix.lower() not in (".md", ".txt"):
                errors.append(f"docs 层不能覆盖非文档改动：{name}")
            if path.suffix.lower() != ".md" or not path.exists():
                continue
            if not path.resolve().is_relative_to(ROOT):
                errors.append(f"拒绝读取仓库外文档：{name}")
                continue
            fence = None
            for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                marker = re.match(r"^\s{0,3}(`{3,}|~{3,})", line)
                if marker:
                    run = marker.group(1)
                    if fence is None:
                        fence = run
                    elif run[0] == fence[0] and len(run) >= len(fence):
                        fence = None
                    continue
                if fence is not None:
                    continue
                line = re.sub(r"`[^`\n]*`", "", line)
                for match in LINK.finditer(line):
                    target = match.group(1).strip("<>")
                    url = urlsplit(target)
                    if url.scheme or url.netloc:
                        continue  # 不探测外部 URL，更不联网。
                    if url.fragment:
                        log.write(f"MANUAL-ANCHOR={name}:{number} {target}\n")
                    if not url.path:
                        continue
                    resolved = (path.parent / unquote(url.path)).resolve()
                    if not resolved.is_relative_to(ROOT):
                        log.write(f"MANUAL-OUTSIDE={name}:{number} {target}\n")
                        continue
                    checked += 1
                    if not resolved.exists():
                        errors.append(f"{name}:{number} 缺少链接目标：{target}")
        for error in errors:
            log.write(error + "\n")
        log.write(f"简单本地目标检查数={checked}\nEXIT={int(bool(errors))}\n")
    if errors:
        raise VerificationFailed(1, "文档机械检查失败，见 documents.txt")


def verify(evidence, args):
    paths = changed_files(evidence, args.base)
    document_checks(evidence, paths, args.layer == "docs")
    if args.layer == "docs":
        return
    requested = os.environ.get("MVN", "mvn")
    mvn = shutil.which(requested)
    if not mvn:
        if shutil.which(requested, mode=os.F_OK):
            raise VerificationFailed(126, f"Maven 文件不可执行：{requested}")
        raise VerificationFailed(127, f"找不到 Maven：{requested}；请设置 MVN=单个可执行路径")
    # which 的相对结果属于调用者目录；subprocess 的 cwd 固定为 ROOT，不能再解析一次。
    mvn = os.path.abspath(mvn)
    evidence.run("maven-version", [mvn, "-v"])
    if args.layer == "focused":
        evidence.run("focused", [mvn, "-B", "-q", "-pl", args.module, "-am", "test"])
    elif args.layer == "full":
        evidence.run("validate", [mvn, "-B", "-q", "validate"])
        evidence.run("package", [mvn, "-B", "-q", "package"])
    elif args.layer == "consumer":
        evidence.run("consumer-candidate", ["bash", str(ROOT / "integration/verify-consumer.sh"), "candidate"],
                     overrides={"MVN": mvn, "CONSUMER_WORK": str(evidence.directory / "consumer-work")})


def main():
    args = arguments()
    try:
        evidence = Evidence(args)
    except OSError as error:
        print(f"无法创建新证据目录：{error}", file=sys.stderr)
        return 2
    code = 0
    try:
        verify(evidence, args)
    except VerificationFailed as error:
        code = error.code
        evidence.state["error"] = str(error)
    except (OSError, UnicodeError, ValueError) as error:
        steps = evidence.state["steps"]
        # 子进程已失败时，后续日志故障不能覆盖它；尚无子进程失败则报编排错误 2。
        code = (steps[-1]["exit"] if steps else None) or 2
        evidence.state["error"] = f"验证或日志 I/O 失败：{error}"
    except KeyboardInterrupt:
        code = 130
        evidence.state["error"] = "验证被中断，未完成"
    evidence.state.update(exit=code, finished=evidence.now())
    try:
        evidence.save()
    except OSError as error:
        print(f"汇总写入失败：{error}", file=sys.stderr)
        return code or 2
    print(f"EXIT={code} EVIDENCE={evidence.directory}")
    if code:
        print(evidence.state["error"], file=sys.stderr)
    else:
        print("所选层机械验证完成；不等于人工放行。测试统计未读取旧 XML。")
    return code


if __name__ == "__main__":
    sys.exit(main())
