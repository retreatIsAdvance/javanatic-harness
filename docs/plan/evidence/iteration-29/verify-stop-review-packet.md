- **不变量增量**：显式验证层、新证据目录、首败停止；失败码不被日志写入失败覆盖，docs 不冒充源码验证。
- **裁决点**：验收本独立脚本交付；不是 it29 停 0 / T0–T7 放行，也不包含提交或推送授权。
- **风险自报**：仅 macOS 真跑；consumer 只测包装契约，未重跑真实消费者腿；文档锚点与复杂引用仍需人工审查。
- **证据入口**：[验证汇总](verify-stop-verification-summary.txt)、[最终22测](verify-stop-final-tests-round2.txt)、[11项突变](verify-stop-mutations-round2.txt)、[最终全量](verify-stop-full-02/summary.txt)。
- **若只能改一处**：保住 `Evidence.run` 的“先开日志、再执行、保留失败码、停止后续”契约；不要把输出捕获改成会吞码的管道。

# Verify Stop Review Packet

2026-10-28。独立方法论停点，**不是 LSP 停 0**。HEAD 保持 `cd39e36b816b82c293ff2f1d77c984179ba1bed6`；全部修改尚未提交，未推送，未运行 LS / 模型 / 发布探针。

## Scope and Contract

入口：[verify-stop.sh](../../../../integration/verify-stop.sh)；实现：[verify_stop.py](../../../../integration/verify_stop.py)；[接口与边界单源](../../iteration-29.md#verification-prerequisite)。Shell 只定位编排器并 `exec`，Python 3.9+ 仅用标准库；没有新的第三方库、JH 模块或 hooks。

```text
bash integration/verify-stop.sh <docs|focused|full|consumer> --log-dir <新目录> [--base <tree-ish>] [--module <模块>]
```

四层共同检查 diff 白空格、变化范围、变动 Markdown 的简单相对链接目标。docs 限定 `.md/.txt`；focused 是 reactor 聚焦；full 依次 validate/package；consumer 是独立 candidate 闭包，**不包含或代替 full**。既有 consumer 的候选 install 跳测试行为未改，不能拿它当全量测试证据。

### Command Selection

以下为 `verify` 的实际契约段，Maven 路径已先按调用者目录固定为绝对路径：

```python
    if args.layer == "focused":
        evidence.run("focused", [mvn, "-B", "-q", "-pl", args.module, "-am", "test"])
    elif args.layer == "full":
        evidence.run("validate", [mvn, "-B", "-q", "validate"])
        evidence.run("package", [mvn, "-B", "-q", "package"])
    elif args.layer == "consumer":
        evidence.run("consumer-candidate", ["bash", str(ROOT / "integration/verify-consumer.sh"), "candidate"],
                     overrides={"MVN": mvn, "CONSUMER_WORK": str(evidence.directory / "consumer-work")})
```

### Failure and Evidence

`Evidence` 以无 `exist_ok` 的 `mkdir()` 创建新目录；每步 `path.open("xb")` 成功后才启动子进程。命令以 argv 数组执行，不经 shell，不用 tee。以下为 `run` 返回边界：

```python
            step["exit"] = code
            log.write(f"\nEXIT={code}\n".encode())
        self.save()
        if code:
            raise VerificationFailed(code, f"{label} 失败，日志：{path}")
        return output
```

双故障处理位于 `main`；已知 child=37 时，即使汇总写入失败，也不得换成 2：

```python
    except (OSError, UnicodeError, ValueError) as error:
        steps = evidence.state["steps"]
        # 子进程已失败时，后续日志故障不能覆盖它；尚无子进程失败则报编排错误 2。
        code = (steps[-1]["exit"] if steps else None) or 2
        evidence.state["error"] = f"验证或日志 I/O 失败：{error}"
```

无有效层、非法参数、无法创建目录均拒绝；日志根目录创建或 Python 启动之前失败只能输出 stderr，不承诺不存在的证据。汇总仍不可写时保留原失败码并报错，不能将半份证据宣称完成。脚本不读取旧 surefire XML，也不转储宿主环境；子命令输出仍须人工查敏感信息。

## Design → Implementation → Tests

| 设计增量 | 实现 | 测试与反证 |
|---|---|---|
| 四显式层，聚焦走 reactor，闭包与全量分开 | `arguments` / `verify`，无任意 Maven 参数透传 | 实际 argv 与顺序断言；M2 去 `-am`、M3 缺 package、M4 错 consumer 腿均红 |
| 首败停止、真失败码、新证据目录 | `Evidence` / `main` | validate=37 不再执行 package；package=19、consumer=23 原样返回；旧目录不改写；日志双故障；M1/M6/M7 均红 |
| 文档范围诚实、工具定位确定 | `changed_files` / `document_checks` / Maven 定位 | docs 拒绝源码与源码符号链接；断链失败；外链不联网；相对工具不被同名替换；不可执行126；M5/M8/M9/M10/M11 均红 |

[行为测试全文](../../../../integration/test_verify_stop.py)；[可复现突变入口](../../../../integration/test_verify_stop_mutations.py)。替身只位于耗时外部命令边界，断言的是实际 wrapper 启动参数、退出码、后续命令是否发生及文件副作用，不是 mock 是否存在。预期 argv / 状态码为独立字面量，不取自生产常量。

## Findings and Deviations

独立只读审查发现 **2 Important + 1 Minor**，主代理先全部复现，再逐项修复：日志排除不得解引用源码符号链接；相对 MVN 必须在切 cwd 前固定；不可执行文件应为126而非127。见[完整评审与处置](verify-stop-review-round1.txt)及[三项修复前红灯](verify-stop-review-red.txt)。最终22测/11突变复核通过；未另做第二轮独立代理审查，不把第一轮报告改写为“最终零问题”。

实现形状补充：使用 8 行 Bash 入口 + Python 标准库编排，而非将参数、NUL 文件名、JSON 汇总和异常传播全部堆进 Bash。既有验证命令没有重写；新增的文档检查只是有限机械检查，不是 CommonMark 完整检查器。

验收边界明确保留：锚点、引用式链接、HTML、复杂 Markdown、源码路径引用、语义及未变文件的反向引用须人工核对；本轮实际 full 为增量 package，不声称 clean 构建或每模块新鲜测试总数。尚未做 Linux / Windows 脚本实跑；真实 consumer 腿未重跑（无组合变化）。这些限制不转写成已经通过的验收项。

## Why This Shape / How Failure Is Detected

显式层名避免自动降档；consumer 单独列出，避免“外部能编译”被误当作“全量测试已过”。每次独立目录使旧日志不能冒充本次结果；同步执行并在第一处非零抛出，使之后的成功命令无法洗掉失败。

最危险的错误不是脚本报红，而是错误地报绿。因此用例直接钉拒绝边界、参数与副作用；突变刻意删除失败传播、reactor 参数、package、候选腿限定及范围检查。11个变异均使行为断言失败，随后原文件逐字节不变、全套22测复绿。

## Evidence

- [实现前红灯](verify-stop-red.txt)：初始18个用例，入口未实现；不存在“先写完再补绿测”。
- [首轮验证汇总及最终补记](verify-stop-verification-summary.txt)：命令、结果、测量面与未覆盖项。
- [最终22个行为测试](verify-stop-final-tests-round2.txt)：`EXIT=0`；[最终11项突变](verify-stop-mutations-round2.txt)：全部断言打红、源码/测试字节不变，驱动返回0。
- [最终 full 汇总](verify-stop-full-02/summary.txt)、[工具版本](verify-stop-full-02/09-maven-version.txt)、[validate](verify-stop-full-02/10-validate.txt)、[package](verify-stop-full-02/11-package.txt)：修复后的脚本实跑，均 `EXIT=0`。
- [真实 focused](verify-stop-focused-01/10-focused.txt)：`-pl kernel/brand -am test`，`EXIT=0`，为评审修复前的命令腿验证。
- [真实 docs 拒绝](verify-stop-docs-rejection-01/documents.txt)：当前含 shell/Python 改动，按约拒绝，`EXIT=1`，不是待修测试失败。

## Stop State

脚本技术交付与取证完成，停在此处等待人工审阅。未提交、未推送，不越入 it29 停 0 或任何 JH LSP 实现；脚本放行与 Git 操作授权分开。
