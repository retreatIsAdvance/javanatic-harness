#!/usr/bin/env bash
# 分层验证入口；Python 3.9+ 仅用标准库。契约见 docs/plan/iteration-29.md。
# bash integration/verify-stop.sh <docs|focused|full|consumer> --log-dir <新目录>
# focused 必须带 --module <仓库相对模块目录>；--base <tree-ish> 缺省 HEAD。
# docs 拒绝变化范围内非 .md/.txt 文件（含暂存与未跟踪文件），不为源码改动背书。
# PYTHON / MVN 可设为单个可执行文件路径（支持空格，不接受命令串）；不自动找旧工具兜底。
set -u
SCRIPT_DIR=$(cd -- "${BASH_SOURCE[0]%/*}" && pwd) || exit 2
exec "${PYTHON:-python3}" "$SCRIPT_DIR/verify_stop.py" "$@"
