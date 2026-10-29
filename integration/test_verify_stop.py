#!/usr/bin/env python3
"""真实启动 wrapper；仅以本地可执行夹具替换昂贵的 Maven / consumer 边界。"""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parent
BASH = shutil.which("bash")
GIT = shutil.which("git")


class VerifyStopTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="verify stop tests ")
        self.addCleanup(self.temp.cleanup)
        self.root = (Path(self.temp.name) / "repo with spaces").resolve()
        self.integration = self.root / "integration"
        self.integration.mkdir(parents=True)
        for name in ("verify-stop.sh", "verify_stop.py"):
            if (SOURCE / name).exists():
                shutil.copyfile(SOURCE / name, self.integration / name)
        self.git("init", "-q")
        # 无提交、无用户 Git 配置改写；index 作为工作树测试基线。
        (self.root / "pom.xml").write_text("<project/>\n")
        self.module = self.root / "core" / "sample module"
        self.module.mkdir(parents=True)
        (self.module / "pom.xml").write_text("<project/>\n")
        (self.root / "guide.md").write_text("# Guide\n")
        self.calls = Path(self.temp.name) / "calls.jsonl"
        self.mvn = self.executable("fake maven", """
import json, os, sys
with open(os.environ['CALLS'], 'a') as out:
    out.write(json.dumps({'argv': sys.argv[1:], 'cwd': os.getcwd()}) + '\\n')
print('fixture stdout')
print('fixture stderr', file=sys.stderr)
if os.environ.get('FAIL_GOAL') == sys.argv[-1]:
    sys.exit(int(os.environ.get('FAIL_CODE', '37')))
""")
        self.consumer = self.integration / "verify-consumer.sh"
        self.consumer.write_text('''#!/bin/bash
"$PYTHON" -c 'import json, os, sys; open(os.environ["CALLS"], "a").write(json.dumps({"consumer": sys.argv[1:], "work": os.environ["CONSUMER_WORK"], "mvn": os.environ["MVN"]}) + "\\n")' "$@"
exit "${CONSUMER_EXIT:-0}"
''')
        self.git("add", ".")
        self.base = self.git("write-tree").stdout.decode().strip()
        self.env = {"PATH": os.environ["PATH"], "HOME": self.temp.name,
                    "PYTHON": sys.executable, "MVN": str(self.mvn),
                    "CALLS": str(self.calls)}
        self.logs = (Path(self.temp.name) / "new evidence").resolve()

    def git(self, *args):
        return subprocess.run([GIT, "-C", str(self.root), *args], check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def executable(self, name, body):
        path = Path(self.temp.name) / name
        path.write_text(f"#!{sys.executable}\n" + body)
        path.chmod(0o755)
        return path

    def run_stop(self, *args, env=None, logs=True):
        command = [BASH, str(self.integration / "verify-stop.sh"), *args,
                   "--base", self.base]
        if logs:
            command += ["--log-dir", str(self.logs)]
        return subprocess.run(command, cwd=self.temp.name, env=env or self.env,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, timeout=30)

    def invocations(self):
        if not self.calls.exists():
            return []
        return [json.loads(line) for line in self.calls.read_text().splitlines()]

    def evidence(self):
        return "\n".join(p.read_text() for p in sorted(self.logs.glob("*.txt")))

    def assert_exit(self, result, code):
        self.assertEqual(code, result.returncode, result.stdout)

    def test_docs_checks_staged_and_untracked_paths_without_maven(self):
        (self.root / "guide.md").write_text("# Guide\n[local](new%20note.md)\n")
        self.git("add", "guide.md")
        (self.root / "new note.md").write_text("# New note\n")
        self.assert_exit(self.run_stop("docs"), 0)
        self.assertEqual([], self.invocations())
        evidence = self.evidence()
        self.assertIn("new note.md", evidence)
        self.assertIn("guide.md", evidence)
        self.assertIn("EXIT=0", evidence)
        self.assertIn("人工", evidence)

    def test_docs_rejects_code_changes_instead_of_claiming_source_verified(self):
        (self.root / "Example.java").write_text("class Example {}\n")
        self.assert_exit(self.run_stop("docs"), 1)
        self.assertIn("Example.java", self.evidence())
        self.assertEqual([], self.invocations())

    def test_broken_relative_link_fails_before_maven(self):
        (self.root / "guide.md").write_text("# Guide\n[broken](missing.md)\n")
        self.assert_exit(self.run_stop("full"), 1)
        self.assertIn("missing.md", self.evidence())
        self.assertEqual([], self.invocations())

    def test_external_and_code_example_links_do_not_trigger_network_or_false_errors(self):
        (self.root / "guide.md").write_text(
            "# Guide\n[web](https://example.invalid/no-fetch)\n"
            "```md\n[example](not-a-real-file.md)\n```\n"
            "`[example](also-not-real.md)`\n[heading](#needs-human-review)\n")
        self.assert_exit(self.run_stop("docs"), 0)
        self.assertIn("人工", self.evidence())

    def test_whitespace_failure_in_index_is_not_hidden_by_clean_worktree(self):
        (self.root / "guide.md").write_text("bad whitespace   \n")
        self.git("add", "guide.md")
        (self.root / "guide.md").write_text("# Clean worktree\n")
        self.assert_exit(self.run_stop("full"), 2)
        self.assertEqual([], self.invocations())
        self.assertIn("EXIT=2", self.evidence())

    def test_focused_runs_reactor_with_module_as_one_argument(self):
        self.assert_exit(self.run_stop("focused", "--module", "core/sample module"), 0)
        self.assertEqual([
            {"argv": ["-v"], "cwd": str(self.root)},
            {"argv": ["-B", "-q", "-pl", "core/sample module", "-am", "test"],
             "cwd": str(self.root)},
        ], self.invocations())

    def test_full_runs_validate_then_package_without_test_skip(self):
        self.assert_exit(self.run_stop("full"), 0)
        self.assertEqual([["-v"], ["-B", "-q", "validate"], ["-B", "-q", "package"]],
                         [call["argv"] for call in self.invocations()])

    def test_validate_failure_preserves_37_and_prevents_package(self):
        self.env.update(FAIL_GOAL="validate", FAIL_CODE="37")
        self.assert_exit(self.run_stop("full"), 37)
        self.assertEqual([["-v"], ["-B", "-q", "validate"]],
                         [call["argv"] for call in self.invocations()])
        evidence = self.evidence()
        self.assertIn("fixture stdout", evidence)
        self.assertIn("fixture stderr", evidence)
        self.assertIn("EXIT=37", evidence)
        self.assertEqual(37, json.loads((self.logs / "summary.txt").read_text())["exit"])

    def test_package_failure_preserves_19(self):
        self.env.update(FAIL_GOAL="package", FAIL_CODE="19")
        self.assert_exit(self.run_stop("full"), 19)
        self.assertIn("EXIT=19", self.evidence())

    def test_consumer_uses_candidate_and_isolates_work_without_running_full(self):
        self.env["CONSUMER_WORK"] = str(self.root / "must-not-reuse")
        self.assert_exit(self.run_stop("consumer"), 0)
        self.assertEqual([
            {"argv": ["-v"], "cwd": str(self.root)},
            {"consumer": ["candidate"], "work": str(self.logs / "consumer-work"),
             "mvn": str(self.mvn)},
        ], self.invocations())
        self.assertIn("CONSUMER_WORK", self.evidence())
        self.assertFalse((self.root / "must-not-reuse").exists())

    def test_consumer_failure_keeps_its_script_exit_code(self):
        self.env["CONSUMER_EXIT"] = "23"
        self.assert_exit(self.run_stop("consumer"), 23)
        self.assertIn("EXIT=23", self.evidence())

    def test_invalid_arguments_and_module_escape_fail_without_running_commands(self):
        for args in [(), ("unknown",), ("full", "--skipTests"), ("focused",),
                     ("docs", "--module", "core/sample module"),
                     ("focused", "--module", "../outside"),
                     ("focused", "--module=-DskipTests"),
                     ("focused", "--module", "core/sample module,other"),
                     ("focused", "--module", "no-such-module")]:
            with self.subTest(args=args):
                self.assert_exit(self.run_stop(*args), 2)
                self.assertEqual([], self.invocations())
                self.assertFalse(self.logs.exists())

    def test_missing_log_directory_argument_is_not_defaulted(self):
        self.assert_exit(self.run_stop("docs", logs=False), 2)
        self.assertFalse(self.logs.exists())

    def test_missing_explicit_maven_does_not_fallback(self):
        self.env["MVN"] = str(Path(self.temp.name) / "missing executable")
        self.assert_exit(self.run_stop("full"), 127)
        self.assertIn("missing executable", self.evidence())
        self.assertEqual([], self.invocations())

    def test_missing_git_fails_loudly(self):
        empty = Path(self.temp.name) / "empty path"
        empty.mkdir()
        self.env["PATH"] = str(empty)
        self.assert_exit(self.run_stop("docs"), 127)
        self.assertIn("git", self.evidence())

    def test_source_symlink_into_evidence_is_still_a_source_change(self):
        (self.root / "evidence").mkdir()
        self.logs = self.root / "evidence" / "new run"
        (self.root / "Example.java").symlink_to(self.logs / "summary.txt")
        self.assert_exit(self.run_stop("docs"), 1)
        self.assertIn("Example.java", self.evidence())
        self.assertEqual([], self.invocations())

    def test_relative_maven_uses_callers_file_not_repository_shadow(self):
        shadow = self.root / "fake maven"
        shadow.write_text(f"#!{sys.executable}\nimport sys\nsys.exit(91)\n")
        shadow.chmod(0o755)
        self.env["MVN"] = "./fake maven"
        self.assert_exit(self.run_stop("full"), 0)
        self.assertEqual([["-v"], ["-B", "-q", "validate"], ["-B", "-q", "package"]],
                         [call["argv"] for call in self.invocations()])

    def test_non_executable_maven_has_126_not_missing_tool_127(self):
        self.mvn.chmod(0o644)
        self.assert_exit(self.run_stop("full"), 126)
        self.assertEqual([], self.invocations())
        self.assertIn("126", self.evidence())

    def test_existing_log_directory_is_never_overwritten(self):
        self.logs.mkdir()
        sentinel = self.logs / "summary.txt"
        sentinel.write_text("previous evidence\n")
        self.assert_exit(self.run_stop("full"), 2)
        self.assertEqual("previous evidence\n", sentinel.read_text())
        self.assertEqual([], self.invocations())

    def test_uncreatable_log_directory_fails_before_running_commands(self):
        parent = Path(self.temp.name) / "not a directory"
        parent.write_text("sentinel\n")
        self.logs = parent / "child"
        self.assert_exit(self.run_stop("full"), 2)
        self.assertEqual([], self.invocations())

    def test_log_write_failure_stops_before_next_command_and_keeps_child_failure(self):
        broken_log_maven = self.executable("log failure maven", """
import os, pathlib, sys
summary = pathlib.Path(os.environ['SUMMARY_TO_BLOCK'])
summary.unlink()
summary.mkdir()
print('completed child before evidence I/O failure')
sys.exit(int(os.environ['CHILD_CODE']))
""")
        self.env.update(MVN=str(broken_log_maven), SUMMARY_TO_BLOCK=str(self.logs / "summary.txt"))
        for child_code, expected in [(0, 2), (37, 37)]:
            with self.subTest(child_code=child_code):
                if self.logs.exists():
                    shutil.rmtree(self.logs)
                self.env["CHILD_CODE"] = str(child_code)
                result = self.run_stop("full")
                self.assert_exit(result, expected)
                self.assertIn("汇总写入失败", result.stdout)
                self.assertEqual([], self.invocations())
                step_logs = [p for p in self.logs.glob("*.txt") if p.is_file()]
                output = "\n".join(p.read_text() for p in step_logs)
                self.assertIn(f"EXIT={child_code}", output)
                self.assertNotIn("-q validate", output)

    def test_summary_does_not_collect_unrelated_environment(self):
        self.env["UNRELATED_PRIVATE_VALUE"] = "synthetic-do-not-record-this-value"
        self.assert_exit(self.run_stop("full"), 0)
        self.assertNotIn("synthetic-do-not-record-this-value", self.evidence())


if __name__ == "__main__":
    unittest.main(verbosity=2)
