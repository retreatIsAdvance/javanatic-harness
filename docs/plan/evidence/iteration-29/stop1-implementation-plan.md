# LSP Seam Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement task-by-task. 本批按用户要求继续当前会话、不再追加确认；停 1 packet 后停，不提交。未获创建工作树授权，不擅自搬动当前修改。

**Goal:** 交付不启动进程的 Java 三能力 Definition 与观察结果不变量。

**Architecture:** 单项目 LspService 隐藏协议与缓存。请求给 URI / 显式时间预算；结果用 sealed 联合区分 Observed、NotReady、Stale、TimedOut。hash 是 JH 观察锚，不能证明 LS 分析来源。

**Tech Stack:** Java 25 final、JPMS、Maven 3.10.0、JUnit5 / AssertJ / jqwik（根 POM 现有依赖）。

**Spec:** [stop1-seam-design.md](stop1-seam-design.md)；[停 0 终裁](stop0-acceptance.txt)。

## Global Constraints

- 永不提交凭据；无新第三方依赖、无 preview、不改 `.java-version`、不削弱门禁。
- 只创建 `lsp/lsp` Definition；不创建 Provider / Consumer、不改 bundle 行 / dist / 外部 consumer。
- T4–T7 未整体批准；≥3启停 / TERM→KILL / server 全应答只在产品验收矩阵中，不冒称本批运行验证。
- 原停 0 packet / 脚本 / 实跑字节冻结。新证据用独立文件，记录命令、输出、退出码。

## Task 1: Observation Values and Rejection Tests

**Files:** `lsp/pom.xml`、`lsp/lsp/pom.xml`、`lsp/lsp/src/main/java/module-info.java`；根 `pom.xml` 加 lsp aggregator 与 harness-lsp-lsp dependencyManagement。主包 `io.javanatic.harness.lsp`；测试同包。

**Produces:** `LspDocument(URI,Optional<String>)`、`LspAnchor(URI,UUID,long,String,LspDocument)`、`LspEvidence(Origin,long,OptionalInt)`、`LspObservation(LspAnchor,LspAnchor,Duration,Duration,Instant,LspEvidence)`、`LspResult<T>` 四变体；各公开值独立同名 Java 文件，包内 `LspValues` 共用正时长 / URI / hash 校验。Optional.empty 只表示确认缺失；Observed 禁止缺失文档，明确 [] 的 version 保留在批次 evidence 中。

- [x] 先写 `LspObservationTest.java`：删除稳定检查、窗口下界、generation/hash校验、集合复制任一项都须被测试发现。核心拒绝用例：

```java
LspEvidence evidence = new LspEvidence(
    LspEvidence.Origin.PUBLISHED_DIAGNOSTICS, 379, OptionalInt.empty());
assertThatIllegalArgumentException().isThrownBy(() ->
    new LspObservation(before, changed, Duration.ofSeconds(1),
        Duration.ofSeconds(1), Instant.EPOCH, evidence));
assertThatIllegalArgumentException().isThrownBy(() ->
    new LspObservation(before, before, Duration.ofSeconds(1),
        Duration.ofMillis(999), Instant.EPOCH, evidence));
```

- [x] 新模块尚无类型时运行 `mvn -B -q -pl lsp/lsp -am test`，将缺失类型编译红独立记录为脚手架红，不冒称断言红。
- [x] 实现值类型：`Objects.requireNonNull`；`generation < 0` 拒绝；`sha.matches("[0-9a-f]{64}")`；`before.equals(after)` 才能 Observed；`elapsedWait.compareTo(requiredWait) < 0` 拒绝；`List.copyOf(items)` 固定快照。Stale 必须同projectRoot/document.uri且锚不同。
- [x] 同命令复绿。通过 jqwik 枚举非负代变化拒绝 Observed，并以字面 1000ms/999ms 保留边界正反例。

## Task 2: Queries, Positions and Failure Vocabulary

**Files:** 主包下 `LspQuery.java`、`LspPositionQuery.java`、`LspPosition.java`、`LspRange.java`、`LspLocation.java`、`LspDiagnostic.java`、`LspErrorCode.java`、`LspException.java`、`LspService.java`；测试 `LspQueryTest.java`。

**Consumes:** Task1 LspResult/Observation。
**Produces:** `LspService.KEY = new ServiceKey<>("lsp")`；diagnostics(query,signal)、definition(positionQuery,signal)、references(positionQuery,signal)、close()；完整类型见 Spec Interface。

- [x] 先写测试：负坐标/倒序range、非正timeout/window、window>timeout、相对/opaque URI拒绝；URI值校验不测虚构权限；diagnostic 的 Optional<String> code/source 缺失保持缺失（version 的 OptionalInt 已在 Task1 批次 evidence 测试）；异常code/cause保留。核心边界：

```java
assertThatIllegalArgumentException().isThrownBy(() ->
    new LspQuery(URI.create("file:///project/App.java"),
        Duration.ofSeconds(1), Duration.ofMillis(1001)));
assertThatIllegalArgumentException().isThrownBy(() ->
    new LspRange(new LspPosition(2, 0), new LspPosition(1, 9)));
```

- [x] 聚焦运行记录类型未实现红，再实现严格字段校验。位置先比line、同行再比character；不限制正整数为ASCII列，不转换UTF-16。
- [x] 三能力接口只有契约/Javadoc，不提供空列表默认方法；close不默认no-op。故不写“fake执行即证明Provider”的测试。
- [x] 聚焦复绿并审查导出面无第三方依赖、无进程/网络/文件读取。

## Task 3: Mutation, Integration and Review Packet

**Files:** 本 evidence 目录下新日志；`docs/design/02-module-layout.md`、`05-capability-seam.md`、`12-api-stability.md`、设计导航、iteration29/current-state。

- [x] 每次只突变一个已验证 guard：观察锚一致性、等待窗下界、items复制、hash格式、负坐标、window≤timeout、缺失文档、quiet非空；审查后增跨项目根与响应version两项，共10项。测试字面量不随变异改动，每项均断言红且逐字节还原，见[首8项](stop1-mutations-01/summary.txt)、[后2项](stop1-mutations-02/summary.txt)。
- [x] 运行 `integration/verify-stop.sh focused --module lsp/lsp --log-dir ...`；`full --log-dir ...`（validate + package）。将 MVN/PYTHON/JAVA_HOME显式设为本机配方；不读取settings或凭据。不跑consumer：组合行未变。见[汇总](stop1-verification-summary.txt)；full 后只补测试，最终[聚焦17项](stop1-final-focused-02.txt)复绿，不混同两次时间面。
- [x] 同步模块表/导出面/当前状态为“Definition交付待审，无可挂载Provider”；T4–T7和真实运行矩阵仍未完成。
- [x] 独立review读最终源码/测试/裁决；处理有效发现，再新鲜机械核验引用目标、冻结证据hash、git diff --check及修改范围。见[设计初审](stop1-review-round1.txt)、[源码复审处置](stop1-review-round2.txt)、[静态核验](stop1-static-check.txt)、[链接检查](stop1-packet-check-01/summary.txt)。
- [x] 已出 [stop1-review-packet.md](stop1-review-packet.md)，首五行TLDR、契约段、三行映射、设计偏离、命令/EXIT及运行缺口齐全；[独立事实核对](stop1-packet-review.txt)未报阻塞发现。用户已通过放行，见 [stop1-acceptance.txt](stop1-acceptance.txt)；勾选表示交付与用户批准，不表示 Provider 运行验收。
