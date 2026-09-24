#!/usr/bin/env bash
# 外部消费方验证（it23，指南 docs/embedding.md §验证）：同一份示例源码、两条工件腿 + 四负例。
#
#   候选腿：主仓工作树 `mvn install` 进隔离本地仓（不碰本机仓库）→ 示例编译 + 治理自证运行 exit 0
#   发布腿：隔离本地仓为空、只取 Maven Central 的 0.1.0 发布件 → 同源复验（交集面）
#           取件走 settings-central.xml（公共代理）：公司 mirror 对 0.1.0 的 16 个 jar 持续 404（pom 正常）
#   负例：坏版本 / 去显式版本 / 坏治理配置 / 生产档撞 AUTO 审批——各须构建必败或 boot 必抛，诊断可读
#
# 用法：integration/verify-consumer.sh [candidate|release|all]   （缺省 all）
# 环境：MVN=<mvn 可执行>  JAVA=<java 可执行>  CONSUMER_WORK=<工作目录>（缺省 mktemp -d）
#       RELEASE_SETTINGS=<settings.xml>（缺省 integration/settings-central.xml；文件不存在则不传 -s）
#       candidate = 候选腿 + 四负例（负例依赖候选构建产物）；release 不进 CI（公网依赖不作门禁）
#       负例④钉 VerifyFailedException 面（PRODUCTION 档禁 AUTO 审批——0.1.0 与 0.2.0 同有），候选腿独有
#       （负例是工具链/组合校验的控制组，只跑候选仓与候选构建产物）

set -u -o pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SAMPLE="$ROOT/integration/consumer-sample"
SELF_CHECK=io.github.retreatisadvance.sample.consumer.SelfCheck
PROFILE="$SAMPLE/profile/consumer-sample.yml"
CANDIDATE_VERSION=$(sed -n 's/^[[:space:]]*<version>\(.*\)<\/version>[[:space:]]*$/\1/p' "$ROOT/pom.xml" | head -1)
LEG=${1:-all}
RELEASE_SETTINGS=${RELEASE_SETTINGS:-$ROOT/integration/settings-central.xml}

failures=0
note() { printf '%s\n' "$*"; }

run() { # run <label> <log> <cmd...>
    local label=$1 log=$2
    shift 2
    printf '+ %s\n' "$*" >"$log"
    if "$@" >>"$log" 2>&1; then
        note "  ok   $label"
        return 0
    fi
    note "  FAIL $label —— 日志尾部（$log）："
    tail -n 25 "$log"
    failures=$((failures + 1))
    return 1
}

expect_fail() { # expect_fail <label> <log> <needle> <cmd...>
    local label=$1 log=$2 needle=$3
    shift 3
    printf '+ %s\n' "$*" >"$log"
    if "$@" >>"$log" 2>&1; then
        note "  FAIL $label —— 期望非零退出，实际成功"
        failures=$((failures + 1))
        return 1
    fi
    if grep -q -- "$needle" "$log"; then
        note "  ok   $label（已拒，文案含「$needle」）"
        return 0
    fi
    note "  FAIL $label —— 诊断文案缺「$needle」；日志尾部："
    tail -n 25 "$log"
    failures=$((failures + 1))
    return 1
}

check() { # check <label> <needle> <file>
    if grep -q -- "$2" "$3"; then
        note "  ok   $1"
        return 0
    fi
    note "  FAIL $1 —— 运行输出缺「$2」（$3）"
    failures=$((failures + 1))
    return 1
}

classpath() { # classpath <leg>
    printf '%s/target/classes:%s' "$SAMPLE" "$(cat "$WORK/classpath-$1.txt")"
}

leg() { # leg <name> <version>
    local name=$1 version=$2
    local repo="$WORK/repo-$name"
    local started=$SECONDS
    local -a settings=()
    if [ "$name" = release ] && [ -f "$RELEASE_SETTINGS" ]; then
        settings=(-s "$RELEASE_SETTINGS")
    fi
    note "== 腿 $name：harness.version=$version，隔离本地仓 $repo =="
    if [ "$name" = candidate ]; then
        run "候选：工作树 install → 隔离仓（跳测试）" "$WORK/install-$name.log" \
            "$MVN" -B -ntp -f "$ROOT/pom.xml" -DskipTests -Dmaven.repo.local="$repo" install
    fi
    run "$name：示例编译" "$WORK/compile-$name.log" \
        "$MVN" -B -ntp ${settings[@]+"${settings[@]}"} -f "$SAMPLE/pom.xml" -Dmaven.repo.local="$repo" \
        -Dharness.version="$version" compile
    run "$name：运行类路径" "$WORK/classpath-$name.log" \
        "$MVN" -B -ntp ${settings[@]+"${settings[@]}"} -f "$SAMPLE/pom.xml" -Dmaven.repo.local="$repo" \
        -Dharness.version="$version" \
        org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath \
        -Dmdep.includeScope=runtime -Dmdep.outputFile="$WORK/classpath-$name.txt"
    run "$name：治理自证运行" "$WORK/run-$name.log" \
        "$JAVA" -cp "$(classpath "$name")" "$SELF_CHECK" "$PROFILE"
    check "$name：自证通过" "SELF-CHECK OK" "$WORK/run-$name.log"
    check "$name：自有工具可见" "word_count.visible=true" "$WORK/run-$name.log"
    check "$name：自有工具可执行" "word_count.invoke.error=false" "$WORK/run-$name.log"
    note "  用时 $((SECONDS - started))s"
}

negatives() {
    local repo="$WORK/repo-candidate"
    note "== 四负例（候选仓 / 候选构建产物）=="
    expect_fail "负例①坏版本（9.9.9）必须解析失败" "$WORK/neg-version.log" "9.9.9" \
        "$MVN" -B -ntp -f "$SAMPLE/pom.xml" -Dmaven.repo.local="$repo" \
        -Dharness.version=9.9.9 compile
    expect_fail "负例②去显式版本必须构建失败" "$WORK/neg-no-version.log" "dependencies.dependency.version" \
        "$MVN" -B -ntp -f "$SAMPLE/negatives/no-version/pom.xml" -Dmaven.repo.local="$repo" validate
    expect_fail "负例③坏治理配置必须 boot 抛错" "$WORK/neg-governance.log" "workspace drift" \
        "$JAVA" -cp "$(classpath candidate)" "$SELF_CHECK" "$SAMPLE/negatives/bad-governance.yml"
    expect_fail "负例④生产档撞 AUTO 审批必须 boot 抛错" "$WORK/neg-production.log" \
        "policy=PRODUCTION 但审批为 AUTO" \
        "$JAVA" -cp "$(classpath candidate)" "$SELF_CHECK" "$PROFILE" PRODUCTION
}

MVN=${MVN:-$(command -v mvn 2>/dev/null || true)}
if [ -z "${MVN:-}" ]; then
    for candidate in "$HOME/.jenv/shims/mvn" "$HOME/Documents/apache-maven-3.8.8/bin/mvn"; do
        if [ -x "$candidate" ]; then
            MVN=$candidate
            break
        fi
    done
fi
if [ -z "${MVN:-}" ]; then
    note "找不到 mvn：设 MVN=<路径>（Maven 3.8+）后重跑"
    exit 2
fi
if [ -z "${JAVA_HOME:-}" ]; then
    resolved=$(/usr/libexec/java_home -v 25 2>/dev/null || true)
    if [ -n "$resolved" ]; then
        export JAVA_HOME=$resolved
    fi
fi
JAVA_RUNTIME=$("$MVN" -v 2>/dev/null | sed -n 's/^Java version:.*runtime: \(.*\)$/\1/p')
JAVA=${JAVA:-${JAVA_RUNTIME:+$JAVA_RUNTIME/bin/java}}
JAVA=${JAVA:-java}
WORK=${CONSUMER_WORK:-$(mktemp -d "${TMPDIR:-/tmp}/consumer-sample.XXXXXX")}
mkdir -p "$WORK" || exit 2

cd "$ROOT" || exit 2
note "仓库：$ROOT"
note "候选版本：$CANDIDATE_VERSION（取自根 pom）"
note "工作目录：$WORK"
note "Maven：$MVN"
"$MVN" -v | sed -n '1,3p'
note "Java：$JAVA"
"$JAVA" -version 2>&1 | sed -n '1,2p'

case "$LEG" in
    all | candidate)
        leg candidate "$CANDIDATE_VERSION"
        if [ -s "$WORK/classpath-candidate.txt" ]; then
            negatives
        else
            note "跳过四负例：候选构建未产出运行类路径"
            failures=$((failures + 1))
        fi
        ;;
    release)
        leg release 0.1.0
        ;;
    *)
        note "用法：$0 [candidate|release|all]"
        exit 2
        ;;
esac

note ""
note "== 汇总：$LEG =="
if [ -f "$WORK/repo-release/io/github/retreatisadvance/harness-bundle-base/0.1.0/_remote.repositories" ]; then
    note "发布腿取件来源（隔离仓初始为空；0.1.0 只可能来自公共 Central 代理，mirror id 见下）："
    sed -n '1,3p' "$WORK/repo-release/io/github/retreatisadvance/harness-bundle-base/0.1.0/_remote.repositories"
fi
note "失败项：$failures"
exit $((failures > 0))
