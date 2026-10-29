#!/usr/bin/env python3
"""只变异临时副本中的编排器，测试字节保持不变；运行真实 CLI 行为测试。"""

import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile


SOURCE = Path(__file__).resolve().parent
MUTATIONS = [
    ("M1-ignore-child-failure", "        if code:\n            raise VerificationFailed(code,",
     "        if False:\n            raise VerificationFailed(code,",
     "test_validate_failure_preserves_37_and_prevents_package"),
    ("M2-omit-reactor", 'args.module, "-am", "test"', 'args.module, "test"',
     "test_focused_runs_reactor_with_module_as_one_argument"),
    ("M3-never-package", '[mvn, "-B", "-q", "package"]', '[mvn, "-B", "-q", "validate"]',
     "test_full_runs_validate_then_package_without_test_skip"),
    ("M4-wrong-consumer-leg", '"integration/verify-consumer.sh"), "candidate"]',
     '"integration/verify-consumer.sh"), "release"]',
     "test_consumer_uses_candidate_and_isolates_work_without_running_full"),
    ("M5-docs-accepts-code", 'if docs_only and path.suffix.lower()',
     'if False and path.suffix.lower()',
     "test_docs_rejects_code_changes_instead_of_claiming_source_verified"),
    ("M6-overwrite-evidence", 'self.directory.mkdir()', 'self.directory.mkdir(exist_ok=True)',
     "test_existing_log_directory_is_never_overwritten"),
    ("M7-log-hides-child-failure", 'code = (steps[-1]["exit"] if steps else None) or 2',
     'code = 2', "test_log_write_failure_stops_before_next_command_and_keeps_child_failure"),
    ("M8-missing-link-passes", 'if not resolved.exists():', 'if False:',
     "test_broken_relative_link_fails_before_maven"),
    ("M9-symlink-hides-source", '(ROOT / path).is_relative_to(evidence.directory)',
     '(ROOT / path).resolve().is_relative_to(evidence.directory)',
     "test_source_symlink_into_evidence_is_still_a_source_change"),
    ("M10-relative-maven-shadow", 'mvn = os.path.abspath(mvn)', 'mvn = mvn',
     "test_relative_maven_uses_callers_file_not_repository_shadow"),
    ("M11-wrong-tool-error", 'if shutil.which(requested, mode=os.F_OK):', 'if False:',
     "test_non_executable_maven_has_126_not_missing_tool_127"),
]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    names = ("verify-stop.sh", "verify_stop.py", "test_verify_stop.py")
    originals = {name: (SOURCE / name).read_bytes() for name in names}
    production = originals["verify_stop.py"].decode()
    failures = 0
    for label, old, new, test in MUTATIONS:
        if production.count(old) != 1:
            print(f"{label}: 变异靶点必须唯一", flush=True)
            return 2
        with tempfile.TemporaryDirectory(prefix="verify-stop-mutant-") as directory:
            copy = Path(directory)
            for name in names:
                (copy / name).write_bytes(originals[name])
            (copy / "verify_stop.py").write_text(production.replace(old, new), encoding="utf-8")
            assert (copy / "test_verify_stop.py").read_bytes() == originals["test_verify_stop.py"]
            command = [sys.executable, str(copy / "test_verify_stop.py"), "VerifyStopTest." + test]
            print(f"\n{label} COMMAND={command!r}", flush=True)
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
            text = result.stdout.decode()
            print(text, end="", flush=True)
            print(f"EXIT={result.returncode}", flush=True)
            # 只认行为断言打红；语法/装配错误不算杀死变异。
            killed = result.returncode == 1 and "AssertionError:" in text and "FAILED (failures=" in text
            print(f"MUTANT_KILLED={killed}", flush=True)
            failures += not killed
    for name, original in originals.items():
        unchanged = (SOURCE / name).read_bytes() == original
        print(f"SOURCE_UNCHANGED={unchanged} SHA256={digest(original)} FILE={name}")
        failures += not unchanged
    print(f"MUTATIONS={len(MUTATIONS)} SURVIVED_OR_INVALID={failures}")
    return int(bool(failures))


if __name__ == "__main__":
    sys.exit(main())
