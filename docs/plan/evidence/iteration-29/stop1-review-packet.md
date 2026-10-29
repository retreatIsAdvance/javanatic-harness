**不变量增量**：仅新增 `lsp/lsp` Definition；四态结果、JH 文件 / 项目观察锚、批次来源及完整等待窗值约束，不证明 LS 分析来源或完成。<br>
**裁决点**：请审单项目三方法 seam、观察值与候选截点算法、拥有 scope / 索引独占 / 无自动重启提案；不是 T4–T7 整体放行。<br>
**风险自报**：纯值可构造不代表真实采样；迟到无 version、ABA、清单外变化仍不可证；Provider 生命周期、协议、消费落账与产品验收均未执行。<br>
**证据入口**：[最终聚焦](stop1-final-focused-02.txt) 17 项、[首8突变](stop1-mutations-01/summary.txt) + [后2突变](stop1-mutations-02/summary.txt)、[full](stop1-full-01/summary.txt)、[复审处置](stop1-review-round2.txt)、[静态核验](stop1-static-check.txt)。<br>
**若只能改一处**：优先审 `LspObservation` 的观察语义与 Provider 截点义务，防止把“值约束成立”误读成“LS 已完成目标版本分析”。

# Stop 1 — LSP Definition Review

## Scope and Decision Boundary

日期：2026-10-29。基线 HEAD `7c8d6a708f42367caa36dbf076d2c7d6398e1728`，本批为工作树修改，未暂存 / 提交 / 推送。依据为[停 0 用户终裁](stop0-acceptance.txt)：停 0 裸进程工程闭环限定通过、JDT LS 路线确认，compiler API 备选不触发；只点名放行停 1 seam 契约。

交付一个 `lsp` 聚合与 `lsp/lsp` 叶子。artifact `harness-lsp-lsp`，JPMS `io.javanatic.harness.lsp`，exports / opens `io.javanatic.harness.lsp`；依赖 `kernel/core` + `llm/llm`，无新第三方。没有 Plugin、ServiceLoader 注册、配置键、事件、Provider、Tool Consumer 或装配行。本地源码声明计数为 62 个 reactor 项目 = 16 聚合（含根）+ 46 叶子，其中 45 代码模块、49 exports；不是组合行或已发布模块计数。

待裁的是最终 seam 形状及下述 Provider 契约提案。T1–T3 的方向已裁不等于这些类型已获批准；T4 hooks / 落账、T5 协议实现 / 依赖 / 容量、T6 路径 / 输入清单 / 映射、T7 配置 / 装配仍未整体批准。Provider 实现须另获点名授权，并完成生命周期矩阵后才能推进消费面闭环；本包不把原停 1 运行要求降格为已验收，也不请求自动进入停 2。

设计全文：[seam 提案](stop1-seam-design.md)；执行与证据映射：[实施计划](stop1-implementation-plan.md)。路线与未完成验收见 [iteration-29](../../iteration-29.md)，语义变更史见 [ADR 0009](../../../../.agents/notes/0009-lsp-observation-anchor.md)。

## Contract Excerpts

### Service and Calling Rules

[LspService.java](../../../../lsp/lsp/src/main/java/io/javanatic/harness/lsp/LspService.java) 的完整方法面（Javadoc 约束紧随其后）：

```java
public interface LspService extends AutoCloseable {
    ServiceKey<LspService> KEY = new ServiceKey<>("lsp");

    LspResult<LspDiagnostic> diagnostics(LspQuery request, AbortSignal signal);
    LspResult<LspLocation> definition(LspPositionQuery request, AbortSignal signal);
    LspResult<LspLocation> references(LspPositionQuery request, AbortSignal signal);

    @Override
    void close();
}
```

- 同步阻塞，可在虚拟线程调用；一个实例对应一个项目，隐藏进程、同步与候选缓存。取消原样抛 `AbortedException`，不翻译为超时；进程 / 协议 / IO 错误走 `LspException`，不能返回假空。
- 诊断 `Observed` 只允许 `QUIET_WINDOW` / `PUBLISHED_DIAGNOSTICS`；定义 / 引用必须有 `REQUEST_RESPONSE`，引用包含声明。**这是方法契约，不是泛型构造器能静态强制的组合约束**，后续 Provider 必须有对应行为测试；本批不写假 Provider 声称已证明。
- 后台只能更新候选，Consumer 先落账再让模型可见；回放零联网、零进程、不重查源码。这些仍是后续执行义务，不是本次纯值运行证明。
- 只有拥有 scope 可调用 `close`，借用者不能关闭；关闭幂等、有界、拒新请求，终结 pending 与旧缓存。失败应上报，停止不回滚 LS 已写文件。

### Observation and Batch Evidence

字段形状：

```java
LspDocument(URI uri, Optional<String> sha256)
LspAnchor(URI projectRoot, UUID incarnation, long generation,
          String projectHash, LspDocument document)
LspEvidence(Origin origin, long receiveSequence, OptionalInt serverVersion)
LspObservation(LspAnchor before, LspAnchor after, Duration requiredWait,
               Duration elapsedWait, Instant capturedAt, LspEvidence evidence)
```

这些是字段清单，不是额外方法。[LspDocument](../../../../lsp/lsp/src/main/java/io/javanatic/harness/lsp/LspDocument.java) 的 Optional.empty 只表示确认文件不存在，不表示读取失败；hash 为小写 64 位 SHA-256。[LspAnchor](../../../../lsp/lsp/src/main/java/io/javanatic/harness/lsp/LspAnchor.java) 校验 root URI、UUID / document 非空、projectHash 格式及 generation 非负；每次启动新 incarnation、代单调增长是 Provider 义务。

[LspEvidence](../../../../lsp/lsp/src/main/java/io/javanatic/harness/lsp/LspEvidence.java) 的关键校验与枚举：

```java
if (receiveSequence < 0) {
    throw new IllegalArgumentException("receiveSequence must be nonnegative");
}
if (origin != Origin.PUBLISHED_DIAGNOSTICS && serverVersion.isPresent()) {
    throw new IllegalArgumentException("only a diagnostics publication can carry serverVersion");
}
public enum Origin {
    QUIET_WINDOW, PUBLISHED_DIAGNOSTICS, REQUEST_RESPONSE
}
```

origin / serverVersion 必须非 null。version 保留 signed integer 原值，仅参考；批次级存储使明确空数组的 version 不丢失，也能区分“无通知”与“明确 []”。receiveSequence 是本地已完整解析消息截点，不是服务端分析序号。

[LspObservation](../../../../lsp/lsp/src/main/java/io/javanatic/harness/lsp/LspObservation.java) 构造器全文：

```java
public LspObservation {
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(after, "after");
    Objects.requireNonNull(elapsedWait, "elapsedWait");
    Objects.requireNonNull(capturedAt, "capturedAt");
    Objects.requireNonNull(evidence, "evidence");
    LspValues.positive(requiredWait);
    if (!before.equals(after)) {
        throw new IllegalArgumentException("observation anchors changed");
    }
    if (after.document().sha256().isEmpty()) {
        throw new IllegalArgumentException("observed document must exist");
    }
    if (elapsedWait.compareTo(requiredWait) < 0) {
        throw new IllegalArgumentException("observation window is incomplete");
    }
}
```

capturedAt 是审计墙钟，等待由单调时钟测量；值对象不能验证时间是怎样测出的、输入是否真的读过。Observed 空诊断只称“拉取时点无诊断”，永不称“项目无错误”。

### Four Results and Failures

[LspResult.java](../../../../lsp/lsp/src/main/java/io/javanatic/harness/lsp/LspResult.java) 的四个变体：

| 变体 | 构造器不变量 | 明确不证明 |
|---|---|---|
| `Observed<T>(LspObservation, List<T>)` | observation 非 null；`List.copyOf` 拒绝 null 并固定条目；QUIET_WINDOW 不得非空 | LS 分析来源 / 完成、Provider 已真实采样 |
| `NotReady<T>(NotReadyReason)` | reason 非 null；INITIALIZING / SYNCHRONIZING / CLOSED | 空结果或项目无错误 |
| `Stale<T>(LspAnchor before, LspAnchor after)` | 两锚非 null；同 projectRoot / document URI；两锚必须不同 | 旧条目可当当前定位 |
| `TimedOut<T>(Duration timeout)` | timeout 为正 | 进程死亡 / 协议错误可吞作空列表 |

[LspQuery](../../../../lsp/lsp/src/main/java/io/javanatic/harness/lsp/LspQuery.java) 的 timeout / observationWindow 为正、window ≤ timeout；总期限从调用进入计，包含同步 / 窗口 / 最终采样，不逐步重置。位置请求复用同一 query；位置零基 UTF-16，range 左闭右开、不可倒序。URI 校验只保证绝对层级语法，不是 realpath / scheme / 权限许可。

[LspDiagnostic](../../../../lsp/lsp/src/main/java/io/javanatic/harness/lsp/LspDiagnostic.java) 的 severity 有 UNSPECIFIED，code / source 可缺失；不能把缺失伪造为 ERROR。异常码为 CONNECTION / PROTOCOL / UNSUPPORTED / IO / CLOSE_FAILED，保留 code / cause；取消另走既有 AbortedException。

## Provider Observation Algorithm — Required, Not Implemented

项目串行观察区内：检查取消 / 状态 / 权限 → 明示输入清单并采 before → 全文 / 变更帧完整写出且本地 flush → 开始明确等待窗 → 与 reader 发布锁互斥，按本地完整消息序号原子复制候选 → 采 after → 稳定且期限内才可 Observed。

本地 flush 不代表 LS 已应用；wire 上未完整解析字节不算已接收候选。reader 截点之后消息留后次，不无界追赶；队列 / reader 溢出必须 PROTOCOL 失败，不能丢消息后合成空。换 incarnation / 已知输入代丢旧缓存；缺 diagnostics 数组是协议错误，warning 不过滤成零。定义 / 引用还必须收到真实请求成功响应，不能从通知沉默造空定位。

前后锚变化给 Stale；起点不存在 / 无法读取给 IO，窗口内删除可由 Optional.empty 表达 Stale。无 version 的迟到分析仍可能混入新窗，ABA 无法发现；未纳入清单的依赖、宿主环境与索引变化仍不可证。具体清单根 / 排除 / symlink / 依赖变化检测、默认窗口值与传输容量在后续 T5/T6 packet 审，不在此偷定。

## Lifecycle, Deviations and Acceptance Matrix

拥有 scope 独占进程与索引目录锁；目录冲突 fail loud，初始化失败回收自有资源，不自动重启（待审提案）。取消单请求不默认关闭项目；关闭后拒新请求，不能再采旧缓存。可信 LS 不入沙箱，工具只读 / 拒批不等于进程无副作用；主动宿主 JDK 发现、项目写入、下载均已登记 [B23–B25](../../boundary-register.md#lsp-boundariesit29已裁决--实施中)。

| 停 0 观察或缺口 | 产品义务 | 本批证明 / 后续验收 |
|---|---|---|
| seq379/400 诊断无 version | 接受且记录缺字段，用 JH 观察锚，不能补盖来源证明 | 本批仅值校验；迟到 / 换代真实行为待验 |
| seq400 是 warning-only，未见 [] | 不修夹具凑零、不将警告过滤；空缓存 / 明确[]可辨 | 本批测 batch metadata；真实无诊断窗待验，无[]不必然违规 |
| shutdown 回 {} 非规范 null | 接受已观察兼容形状并记录；错误响应不假成功 | null / {} 两分支与错误帧待验 |
| initialized 甚至 initialize 响应前有 progress 请求 | reader 自启动服务双向请求并记录顺序偏离；未知请求 -32601 | 初始化在途 server 请求待验 |
| 412 帧 journal 内18请求全答；尾流id19未答 | shutdown / exit 后继续协议解析与应答，不能立即 raw drain；未完成超时须失败取证 | 全请求配对 / 退出洪流 / 有界关闭待验；尾流无逐帧时间，不断言全在exit之后 |
| 单次正常退出不足 | 同钉定 build ≥3次启停，逐次 exit / EOF / pending / 线程 / 专属组证据 | **未执行**，不以纯值测试或停0单次替代 |
| TERM忽略路径无产品证据 | 有界宽限后 KILL，及时wait，复验专属组；EPERM不是消失，失败 CLOSE_FAILED | **未执行**；可控子进程夹具须调用产品同一关闭实现 |
| 全权进程主动探测与写盘 | 私有 HOME/env不是OS隔离；停止不回滚写盘，快照非全机审计 | 停0事实保留；Provider配置 / 目录锁 / 失败回收待验 |

原始事实与偏离见[停 0 冻结 packet](stop0-review-packet.md)、[尾流分析](stop0-run-01/protocol-tail-analysis.txt)、[有效设置](stop0-run-01/effective-settings.txt)。版本仍为官方 milestones `1.63.0 / 202610191322`，内部 SNAPSHOT 字段保留；实验只覆盖单 Maven + JPMS、macOS arm64，不外推全仓或四平台。`tools/list_changed` 是通知纪律类比，不编造 JDT 发过 MCP 方法。

只回收自有进程，不按 Java 名称批量杀；数字 PID/PGID 快照不保证任意脱组后代，Windows / 其他平台的所有权方式与限制须另列证据。

## Design → Implementation → Verification

| 设计增量 | 实现 | 测试 / 尚缺 |
|---|---|---|
| D1 三能力 Definition、协议隐藏 | LspService / Query / Position / Range / Location / Diagnostic、JPMS 与 POM | [LspQueryTest](../../../../lsp/lsp/src/test/java/io/javanatic/harness/lsp/LspQueryTest.java) 6项；M5/M6；full构建与jar描述。真实三能力未交付 |
| D2 观察锚、批次证据、四态 | LspAnchor / Document / Evidence / Observation / Result | [LspObservationTest](../../../../lsp/lsp/src/test/java/io/javanatic/harness/lsp/LspObservationTest.java) 10 JUnit +1 property；M1–M4/M7–M10；不是采样 / 队列 / 落账证明 |
| D3 权限 / 生命周期；D4装配暂不实施 | Service Javadoc、seam算法与上表；05/06/边界登记同步，无运行实现或装配 | 独立设计 / 源码审查；≥3/KILL/server全应答与R1/R2/consumer闭包全部待后续授权验证 |

## Review, Mutation and Execution Evidence

[设计初审](stop1-review-round1.txt)报 3 Important +1 Minor：同步与截点不明、批次 version 丢空数组、删除无法表达、位置请求形状缺失；已分别落实到契约和值类型。[源码复审](stop1-review-round2.txt)未报 Critical / Important，报2 Minor测试漏检：不同项目根 Stale、REQUEST_RESPONSE 携 version。生产守卫原本正确，新增拒绝断言后 M9/M10 必红。协调者另将测试辅助 switch 的 fail-loud default 对齐仓库规范；没有新增产品行为。

审查员只读、没有运行 Maven / LS；修正后由协调者验证，不冒称审查员复审批准。方法为会话内实现加独立设计 / 源码审查，不宣称完整 SDD 流程。最初缺类型红属于脚手架编译红，行为有效性由后续突变断言红另证。另有[最终 packet 事实核对](stop1-packet-review.txt)，未报阻塞发现；[链接 / 文本核验](stop1-packet-check-01/summary.txt)为 static-only，不冒称又跑过 Maven。

| 证据 | 实际命令 / 结果 |
|---|---|
| [初始观察红](stop1-observation-red.txt)、[观察复绿](stop1-observation-green.txt)、[查询红](stop1-query-red.txt) | `mvn -B -q -pl lsp/lsp -am test`；缺类型编译红与复绿分开，不当行为断言红 |
| [突变脚本](stop1-mutations.py)、[M1–M8](stop1-mutations-01/summary.txt)、[M9–M10](stop1-mutations-02/summary.txt) | 显式 JAVA_HOME/MVN，`python3.12 -B …/stop1-mutations.py` 及同命令加 `review`；每项 Maven EXIT=1、命中目标 assertion failure、errors=[]；两批编排 EXIT=0，全部源码原字节恢复，测试在变异时不变 |
| [focused-02](stop1-focused-02/summary.txt) | `bash integration/verify-stop.sh focused --module lsp/lsp --log-dir docs/plan/evidence/iteration-29/stop1-focused-02`，EXIT=0，初始8突变后复绿 |
| [full-01](stop1-full-01/summary.txt)、[validate](stop1-full-01/10-validate.txt)、[package](stop1-full-01/11-package.txt) | `bash integration/verify-stop.sh full --log-dir docs/plan/evidence/iteration-29/stop1-full-01`；内部 `mvn -B -q validate` / `package` 均 EXIT=0 |
| [最终 focused-02](stop1-final-focused-02.txt) | `/Users/abel_yang/Documents/apache-maven-3.10.0/bin/mvn -B -pl lsp/lsp -am test`，LSP模块 `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS，EXIT=0 |
| [构建 / 计数 / hash汇总](stop1-verification-summary.txt)、[静态核验](stop1-static-check.txt) | 产出jar的JPMS描述、源码hash对比、62/16/46/45/49声明计数、git范围与diff检查、7个停0冻结文件SHA一致；EXIT=0 |
| [普通日志规范化记录](stop1-evidence-normalization.txt) | 仅已结束的普通停1 Maven日志移除ANSI/尾空白；不是原始协议字节证据，停0不改 |

所有实际 Maven 路径与 JAVA_HOME 均在日志中；wrapper 指定 Python 3.12，未读取 settings / 凭据或整份环境。构建工具可能下载依赖，不能将 Maven 验证称为离线；没有启动新的 LS / 模型、consumer 或发布探针。

**时间与计数面**：full-01 于 08:14:43–08:16:31 UTC 完成，随后只增测试拒绝断言和测试辅助 default；最终 focused-02 于 08:23:29–08:23:37 UTC 完成。生产 Definition 和 lsp POM hash 与 full 时一致，不能说新增测试在 full 中执行过。17 =16 JUnit方法 +1 jqwik属性；100 tries不额外计测试。最初残留XML合计7，是同名类被不同引擎覆盖后的文件测量，原数保留并明确纠正，**不能当模块执行总数**。没有计算全reactor测试总数；现有 Unsafe 等警告保留，成功不等于无warning。

## Deviations, Risks and Why This Shape

单项目三方法 Interface 把协议、缓存和进程知识集中在一个 Module；不暴露 didOpen / 裸 JSON-RPC、不先建多 server registry，避免调用者各写状态机。Definition 目前只有未来真实 Provider 与 keyless替身的预留 seam，不以“已有多个 Adapter”论证已经获得的深度；是否足够由后续固定任务和消费者实现检验。

当前字段较纯列表多，是为了保留“看到什么 / 何时采样 / 什么来源 / 是否完整窗”的审计面。若该形状错误，纯值层由拒绝测试与10项突变发现，Provider层由迟到通知 / 删除 / 输入漂移 / 错误帧 / 超时 / 生命周期矩阵发现，消费层由落账前可见性与R1请求重建发现；本批只能证明第一层。

尚存明确限制：方法与 Origin 的组合靠行为契约；所有权与幂等关闭不能被 interface 强制；输入清单和 URI 权限尚未实现，不能宣传安全读取；全权进程副作用不由工具拒批消除。以上不是被单测“覆盖”的已交付行为。

停0冻结下载头9份各有第1行尾空白，已只读记录，未修改。`git diff --check` 对tracked/index绿不证明这些未跟踪原始头可直接提交；保留原字节与提交门禁的处理须提交前单独裁定，本包不绕hook、不改规则。另有README旧构建计数、02旧目录树及历史release计数等既有漂移不属于本批修理面，不宣称全仓文档零漂移。

## Requested Disposition

请评审本包的最终 Definition、观察算法与生命周期契约提案。**已停在停 1：未提交 / 推送，不实现 Provider / Consumer，不进入下一锚点。** T4–T7、真实生命周期、三能力产品验收和0.3.0发布均保持未批准 / 未完成的相应状态。
