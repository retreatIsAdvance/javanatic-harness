#!/usr/bin/env bash
# it29 停2-S4 门禁突变脚本入口：7 条停 2 门禁「注入突变必红 → 字节级还原 → 复绿」。
# bash integration/verify-stop2-mutations.sh [--repo-root PATH] [--log-dir DIR]
# 环境：JAVA_HOME 必填；MVN 可设为 mvn 可执行文件路径（缺省 PATH 上的 mvn）。
# 零联网；运行前后被改文件逐字节一致，不改变 git 状态。
set -u
SCRIPT_DIR=$(cd -- "${BASH_SOURCE[0]%/*}" && pwd) || exit 2
exec "${PYTHON:-python3}" "$SCRIPT_DIR/verify_stop2_mutations.py" "$@"
