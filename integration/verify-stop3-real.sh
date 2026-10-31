#!/usr/bin/env bash
# it29 停3-S4 真实 JDT LS 三能力探针入口：生产 JdtlsProvider 接钉定真机（停 0 解包工件 + 停 0 夹具复制到自有运行目录），
# ≥3 启停逐轮取证：诊断（PUBLISHED 真实批次）→ 定义（textDocument/definition 真实响应）→ 引用（textDocument/references 含声明），
# 每轮 close 后复验无残留。证据如实打印，退出码 0 = 全部断言通过；1 = 任一断言失败（不凑绿）。
#
# 用法：bash integration/verify-stop3-real.sh --run-root DIR --stop0-root DIR [--rounds N]
#   --run-root   本次运行目录（probe 会先清空重建：project/index/home + evidence 快照）
#   --stop0-root 停 0 解包根（含 server-1.63.0、config_mac_arm、project 夹具、home/.m2 暖仓库）
# 环境：JAVA_HOME 必填（钉定 Temurin 25）；M2E_REPO 可设为本地 jackson 2.17.0 所在仓库根（缺省 ~/Documents/m2e/repository）。
# 前置：lsp/jdtls 已 test-compile（类路径上 target/classes 与 test-classes 必须存在）。
# 零联网（LS 依赖经 stop0-root/home/.m2/repository 暖仓供入；暖仓缺失时 LS 可能联网补依赖）。
set -u
SCRIPT_DIR=$(cd -- "${BASH_SOURCE[0]%/*}" && pwd) || exit 2
REPO_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd) || exit 2

RUN_ROOT=
STOP0_ROOT=
ROUNDS=3
while [ "$#" -gt 0 ]; do
    case "$1" in
        --run-root) RUN_ROOT=$2; shift 2 ;;
        --stop0-root) STOP0_ROOT=$2; shift 2 ;;
        --rounds) ROUNDS=$2; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

[ -n "${JAVA_HOME:-}" ] || { echo "JAVA_HOME is required (pinned Temurin 25)" >&2; exit 2; }
[ -n "$RUN_ROOT" ] || { echo "--run-root is required" >&2; exit 2; }
[ -n "$STOP0_ROOT" ] || { echo "--stop0-root is required" >&2; exit 2; }
[ -d "$STOP0_ROOT/server-1.63.0" ] || { echo "--stop0-root lacks server-1.63.0: $STOP0_ROOT" >&2; exit 2; }
[ -d "$STOP0_ROOT/config_mac_arm" ] || { echo "--stop0-root lacks config_mac_arm: $STOP0_ROOT" >&2; exit 2; }

CP="$REPO_ROOT/lsp/jdtls/target/classes:$REPO_ROOT/lsp/jdtls/target/test-classes:$REPO_ROOT/lsp/lsp/target/classes:$REPO_ROOT/kernel/core/target/classes:$REPO_ROOT/llm/llm/target/classes"
for jar in jackson-databind jackson-core jackson-annotations; do
    JAR_PATH="${M2E_REPO:-$HOME/Documents/m2e/repository}/com/fasterxml/jackson/core/$jar/2.17.0/$jar-2.17.0.jar"
    [ -f "$JAR_PATH" ] || { echo "missing jackson jar: $JAR_PATH (set M2E_REPO)" >&2; exit 2; }
    CP="$CP:$JAR_PATH"
done
PROBE_CLASS="$REPO_ROOT/lsp/jdtls/target/test-classes/io/javanatic/harness/lsp/jdtls/JdtlsThreeCapabilityProbe.class"
[ -f "$PROBE_CLASS" ] || { echo "JdtlsThreeCapabilityProbe not compiled; run mvn -pl lsp/jdtls -am test-compile first" >&2; exit 2; }

exec "$JAVA_HOME/bin/java" -cp "$CP" io.javanatic.harness.lsp.jdtls.JdtlsThreeCapabilityProbe \
    --run-root "$RUN_ROOT" --stop0-root "$STOP0_ROOT" --java-home "$JAVA_HOME" --rounds "$ROUNDS"
