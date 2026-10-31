#!/usr/bin/env python3
"""it29 停2-S4 门禁突变脚本：对停 2 实现的 7 条关键门禁逐一注入突变，
聚焦测试必红 → 字节级还原（与运行前逐字节一致）→ 复绿。零联网、不建新进程之外
不修改工作区（每个文件运行前后内容一致，不改变 git 状态）。

用法：python3 verify_stop2_mutations.py [--repo-root PATH] [--log-dir DIR]
环境：JAVA_HOME 必填（mvn 起 java 进程所需）；MVN 可设为 mvn 可执行文件路径，
      缺省用 PATH 上的 mvn。

退出码：0 = 全部突变「红+绿」达标且还原逐字节一致；2 = 用法错误；
       1 = 任一突变红/绿判定失败、还原不一致或运行环境缺失。

红判定：mvn test 退出码非 0 且 surefire 汇总出现 <<< FAILURE!/<<< ERROR! 标记；
       编译/门禁失败（无测试失败标记）不算红，单独报错。
绿判定：退出码 0 且汇总行含 "Failures: 0, Errors: 0"。
"""
import argparse
import os
import re
import subprocess
import sys
import time

PROVIDER = "lsp/jdtls/src/main/java/io/javanatic/harness/lsp/jdtls/JdtlsProvider.java"
FIXTURES = "lsp/jdtls/src/test/java/io/javanatic/harness/lsp/jdtls/Fixtures.java"
BACKPRESSURE = "lsp/jdtls/src/test/java/io/javanatic/harness/lsp/jdtls/PeerBackpressureServer.java"

MUTATIONS = [
    {
        "name": "M1 R3-1 同步账本退到 flush 之后",
        "file": PROVIDER,
        "edits": [
            (
                "                    CompletableFuture<Void> flushed =\n"
                "                        peer.enqueueNotify(\"textDocument/didClose\", params, call.signal);\n"
                "                    openedDocuments.remove(uri); // 入队即 wire 承诺：帧不被撤销，本地状态与 wire 同进退\n"
                "                    awaitSync(flushed, call);",
                "                    CompletableFuture<Void> flushed =\n"
                "                        peer.enqueueNotify(\"textDocument/didClose\", params, call.signal);\n"
                "                    awaitSync(flushed, call);\n"
                "                    openedDocuments.remove(uri); // 突变：账本退到 flush 之后",
            ),
            (
                "            openedDocuments.put(uri, new OpenDocument(hash, version)); // 入队即 wire 承诺\n"
                "            awaitSync(flushed, call);",
                "            awaitSync(flushed, call);\n"
                "            openedDocuments.put(uri, new OpenDocument(hash, version)); // 突变：账本退到 flush 之后",
            ),
        ],
        "test": "JdtlsProviderBudgetTest#syncTimeoutStillCommitsThenRevertSendsDidChange"
        "+syncCancelledStillCommitsThenRevertSendsDidChange",
    },
    {
        "name": "M2 R3-2 溢出检查移到 Stale 分支之后",
        "file": PROVIDER,
        "edits": [
            (
                "        call.check();\n"
                "        LspAnchor afterAnchor = anchor(after, uri);\n"
                "        // 截点前溢出毒化当轮拉取（PROTOCOL 优先于 Stale：溢出是窗口内协议事件，输入竞态不得吞掉）。\n"
                "        if (candidate.overflowed()) {\n"
                "            throw new LspException(LspErrorCode.PROTOCOL, \"diagnostics overflow for document \" + uri);\n"
                "        }\n"
                "        if (!beforeAnchor.equals(afterAnchor)) {\n"
                "            return new LspResult.Stale<>(beforeAnchor, afterAnchor);\n"
                "        }",
                "        call.check();\n"
                "        LspAnchor afterAnchor = anchor(after, uri);\n"
                "        if (!beforeAnchor.equals(afterAnchor)) {\n"
                "            return new LspResult.Stale<>(beforeAnchor, afterAnchor);\n"
                "        }\n"
                "        // 突变：溢出检查移到 Stale 分支之后（R3-2 旧缺陷复现）\n"
                "        if (candidate.overflowed()) {\n"
                "            throw new LspException(LspErrorCode.PROTOCOL, \"diagnostics overflow for document \" + uri);\n"
                "        }",
            ),
        ],
        "test": "JdtlsProviderSamplingTest#overflowFrozenInSameRoundAsInputChangeReportsProtocolThenRecovers",
    },
    {
        "name": "M3 R3-3 采样登记互斥摘除（去 synchronized）",
        "file": PROVIDER,
        "edits": [
            (
                "        synchronized (lifecycleLock) {\n"
                "            beforeSamplingRegistration.run();\n"
                "            call.checkState();\n"
                "            samplingThread = Thread.ofVirtual().name(\"jh-lsp-\" + serverId + \"-sample\").unstarted(task);\n"
                "            samplingThread.start();\n"
                "        }",
                "        beforeSamplingRegistration.run();\n"
                "        call.checkState();\n"
                "        samplingThread = Thread.ofVirtual().name(\"jh-lsp-\" + serverId + \"-sample\").unstarted(task);\n"
                "        samplingThread.start();",
            ),
        ],
        "test": "JdtlsProviderSamplingTest#closeMutuallyExcludesSamplingRegistrationAndRejectsAfterwards",
    },
    {
        "name": "M4 R3-4 validateInitialize 校验整体摘除",
        "file": PROVIDER,
        "edits": [
            (
                "    private static void validateInitialize(JsonNode result) {\n"
                "        if (result == null || !result.isObject()) {\n"
                "            throw new LspException(LspErrorCode.PROTOCOL, \"initialize response result must be an object: \"\n"
                "                + (result == null ? \"null\" : result.getNodeType().name().toLowerCase()));\n"
                "        }\n"
                "        JsonNode capabilities = result.get(\"capabilities\");\n"
                "        if (capabilities == null || !capabilities.isObject()) {\n"
                "            throw new LspException(LspErrorCode.PROTOCOL, \"initialize response must declare capabilities\");\n"
                "        }\n"
                "        JsonNode sync = capabilities.get(\"textDocumentSync\");\n"
                "        boolean syncSupported;\n"
                "        if (sync == null) {\n"
                "            syncSupported = false;\n"
                "        } else if (sync.isIntegralNumber()) {\n"
                "            syncSupported = sync.intValue() >= 1; // 1=Full、2=Incremental；0/缺省=None\n"
                "        } else if (sync.isObject()) {\n"
                "            syncSupported = sync.path(\"change\").asInt(0) >= 1 && sync.path(\"openClose\").asBoolean(false);\n"
                "        } else {\n"
                "            syncSupported = false;\n"
                "        }\n"
                "        if (!syncSupported) {\n"
                "            throw new LspException(LspErrorCode.PROTOCOL,\n"
                "                \"initialize response declares no text document sync usable for full-text pushes\");\n"
                "        }\n"
                "    }",
                "    private static void validateInitialize(JsonNode result) {\n"
                "        // 突变：R3-4 校验整体摘除（评审所述漏洞本尊）\n"
                "    }",
            ),
        ],
        "test": "JdtlsProviderProtocolTest#initializeResultWithoutUsableSyncFailsProtocol"
        "+initializeResultWithObjectSyncAcceptsIncrementalShape",
    },
    {
        "name": "M5 R3-4 掩码恢复（checkLifecycle → checkState 三处）",
        "file": PROVIDER,
        "edits": [
            (
                "        while (!initialized.isDone()) {\n"
                "            call.checkLifecycle();\n",
                "        while (!initialized.isDone()) {\n"
                "            call.checkState();\n",
            ),
            (
                "        try {\n"
                "            // 初始化失败已存储时不得被健康检查抢先掩盖（rollback 已关闭 peer）：先验未来，后验健康。\n"
                "            call.checkLifecycle();\n"
                "            if (!checkInitialized(call)) {\n"
                "                call.checkLifecycle();\n",
                "        try {\n"
                "            call.checkState();\n"
                "            if (!checkInitialized(call)) {\n"
                "                call.checkState();\n",
            ),
        ],
        "test": "JdtlsProviderProtocolTest#initializeResultWithoutUsableSyncFailsProtocol"
        "+initializeResultWithObjectSyncAcceptsIncrementalShape",
    },
    {
        "name": "M6 Fixtures 空文件窗口竞态（恒 null 上界确定性红）",
        "file": FIXTURES,
        "edits": [
            (
                "            if (!Files.exists(file)) {\n"
                "                return null;\n"
                "            }\n"
                "            String text = Files.readString(file);\n"
                "            return text.isEmpty() ? null : text; // 空文件 = 对端尚未写完（writeString 非原子），继续轮询",
                "            if (!Files.exists(file)) {\n"
                "                return null;\n"
                "            }\n"
                "            // 突变：恒 null 上界（空窗口竞态无法确定性红）——保留读面与 IOException 可达面，\n"
                "            // 使外层 catch(IOException) 仍可编译、Files 导入仍被使用，内容一律弃用。\n"
                "            Files.readString(file);\n"
                "            return null;",
            ),
        ],
        "test": "JdtlsProviderTest#dedicatedProcessGroupMatchesPid",
    },
    {
        "name": "M7 背压夹具能力声明摘除（capabilities 空对象）",
        "file": BACKPRESSURE,
        "edits": [
            (
                "{\\\"capabilities\\\":{\\\"textDocumentSync\\\":1}}",
                "{\\\"capabilities\\\":{}}",
                2,  # 两处 initialize 应答（blocked/provider-blocked 与 sync-blocked）
            ),
        ],
        "test": "JdtlsProviderBudgetTest#syncCancelledStillCommitsThenRevertSendsDidChange"
        "+syncTimeoutStillCommitsThenRevertSendsDidChange"
        "+closeFailsLoudlyWhenParsedReplyCannotFlushWithinDrainGrace"
        "+closeAfterStdoutEofFlushesRepliesThenReportsUnansweredShutdown",
    },
]

FAIL_MARKER = re.compile(r"<<< (FAILURE|ERROR)!")
GREEN_MARKER = "Failures: 0, Errors: 0"


def log(message):
    print(message, flush=True)


def read_bytes(path):
    with open(path, "rb") as handle:
        return handle.read()


def apply_edits(content, edits):
    for edit in edits:
        old, new = edit[0], edit[1]
        expected = edit[2] if len(edit) > 2 else 1
        count = content.count(old.encode("utf-8"))
        if count != expected:
            raise ValueError(
                "old 串出现 %d 次（期望 %d 次）：%r..." % (count, expected, old[:60])
            )
        content = content.replace(old.encode("utf-8"), new.encode("utf-8"))
    return content


def run_mvn(mvn, repo_root, test_selector, log_path):
    command = [
        mvn, "-B", "-q", "-pl", "lsp/jdtls", "-am", "test",
        "-Dtest=" + test_selector,
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dorg.slf4j.simpleLogger.log.org.apache.maven.plugin.surefire.SurefirePlugin=info",
    ]
    with open(log_path, "wb") as handle:
        completed = subprocess.run(command, cwd=repo_root, stdout=handle, stderr=subprocess.STDOUT)
    return completed.returncode


def summarize(log_path):
    with open(log_path, "rb") as handle:
        text = handle.read().decode("utf-8", errors="replace")
    summary = None
    for line in text.splitlines():
        if "Tests run:" in line and "-- in" not in line:
            summary = line.strip()
        if FAIL_MARKER.search(line):
            first_failure = line.strip()
            return summary, first_failure
    return summary, None


def main():
    parser = argparse.ArgumentParser(description="it29 停2-S4 门禁突变检查")
    parser.add_argument("--repo-root", default=os.getcwd())
    parser.add_argument("--log-dir", default=None)
    args = parser.parse_args()

    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        log("FAIL: 需要 JAVA_HOME 环境变量")
        return 1
    mvn = os.environ.get("MVN", "mvn")
    repo = os.path.abspath(args.repo_root)
    log_dir = args.log_dir or "/tmp/verify-stop2-mutations-%s" % time.strftime("%Y%m%d-%H%M%S")
    os.makedirs(log_dir, exist_ok=True)

    failures = []
    for mutation in MUTATIONS:
        name = mutation["name"]
        path = os.path.join(repo, mutation["file"])
        log("== %s" % name)
        try:
            original = read_bytes(path)
            mutated = apply_edits(original, mutation["edits"])
        except (OSError, ValueError) as error:
            log("FAIL: 突变注入失败：%s" % error)
            failures.append(name)
            continue
        if mutated == original:
            log("FAIL: 突变注入后内容未变化（new==old 或未命中）")
            failures.append(name)
            continue
        with open(path, "wb") as handle:
            handle.write(mutated)

        red_log = os.path.join(log_dir, "red-%s.log" % name.split()[0])
        red_rc = run_mvn(mvn, repo, mutation["test"], red_log)
        red_summary, red_first = summarize(red_log)
        red_ok = red_rc != 0 and FAIL_MARKER.search(open(red_log, "rb").read().decode("utf-8", errors="replace")) is not None
        if red_ok:
            log("RED  OK  exit=%d  %s  %s" % (red_rc, red_summary or "?", red_first or ""))
        else:
            log("RED  FAIL exit=%d  %s（无测试失败标记 = 编译/门禁失败或未红）" % (red_rc, red_summary or "?"))

        with open(path, "wb") as handle:
            handle.write(original)
        if read_bytes(path) != original:
            log("FAIL: 还原后与运行前逐字节不一致")
            failures.append(name)
            continue
        green_log = os.path.join(log_dir, "green-%s.log" % name.split()[0])
        green_rc = run_mvn(mvn, repo, mutation["test"], green_log)
        green_summary, _ = summarize(green_log)
        green_ok = green_rc == 0 and GREEN_MARKER in open(green_log, "rb").read().decode("utf-8", errors="replace")
        if green_ok:
            log("GREEN OK  exit=%d  %s" % (green_rc, green_summary or "?"))
        else:
            log("GREEN FAIL exit=%d  %s" % (green_rc, green_summary or "?"))
        if not (red_ok and green_ok):
            failures.append(name)

    log("== 汇总：%d/%d 突变红绿达标，日志目录 %s" % (len(MUTATIONS) - len(failures), len(MUTATIONS), log_dir))
    if failures:
        log("FAIL: %s" % ", ".join(failures))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
