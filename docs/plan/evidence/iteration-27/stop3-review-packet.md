# it27 停 3 review packet：组合面 + 验收取证

- 基线：`08cc1e9`（停 2 提交后；停 1 `be14c94`、停 2 `08cc1e9` 均未推送）
- 范围：`bundle/base` 两行 + 行集变化四处消费面（含内部第五处）+ `--verify` mcp 摘要
  + 夹具富 schema 真跑 + R1 端到端回放；**不 commit、不越收口**
- 工作树：13 个修改文件 + 2 处新增（`mcp/stdio` 的 services 文件、
  `examples/headless/src/test/resources/verify-fixture/`）

## 1. 承载面

### 1.1 bundle 两行（`bundle/base/src/main/resources/META-INF/harness/bundle.yml`）

```yaml
  - plugin: skill-tool
  # MCP 工具生态（it27）：stdio transport 与工具消费。servers 缺省为空 = 零噪声
  # （不拉进程、不落事件、不注册工具）；装配期拉起全部已配 server（成本口径 07）。
  # server 是 harness 直接子进程、以用户全权运行（不入沙箱——治理边界在工具调用面）。
  # 工具名 mcp__<server>__<raw>，恒需审批（approvalExempt=false，无豁免出口）。
  - plugin: mcp-stdio
    config:
      servers: []
  - plugin: mcp-tool
  # 单 agent 顺序工作——并行 in_progress 需 subagent/workflow 场景再开
  - plugin: todo
```

内部测试闭包同步：`bundle/base/pom.xml` 增 `harness-mcp-stdio` / `harness-mcp-tool`
（test scope），`AppBootTest.discover` 发现清单追加 `"mcp-stdio", "mcp-tool"`。

### 1.2 `--verify` 摘要 mcp 行（`HeadlessMain`）

行插入（approval 与 audit 之间）：

```java
"  mcp: " + mcpText(root),
```

承载方法全文：

```java
/**
 * MCP 声明面（it27）：base 两行在场 + 已注册工具（名称序）。工具名恒
 * {@code mcp__<server>__<raw>}、恒需审批（approvalExempt=false，插件无豁免出口）——
 * 有工具时注记 approval-required，与 approval 行的 exempt 名单互证。
 * 两行被 profile 移除时印 `-`（自定义组合合法，不猜）。
 */
private static String mcpText(Scope root) {
    List<String> rows = root.require(CompositionManifest.KEY).rows().stream()
        .map(CompositionManifest.Row::plugin)
        .filter(plugin -> plugin.equals("mcp-stdio") || plugin.equals("mcp-tool"))
        .toList();
    List<String> tools = root.resolve(ToolRegistry.KEY)
        .map(registry -> registry.definitions(root).stream()
            .map(ToolDefinition::name)
            .filter(name -> name.startsWith("mcp__"))
            .sorted()
            .toList())
        .orElseGet(List::of);
    String mounted = rows.isEmpty() ? "-" : String.join(" + ", rows);
    return tools.isEmpty()
        ? mounted + "; tools=0"
        : mounted + "; tools=" + String.join(",", tools) + " (approval-required)";
}
```

`docs/design/07-profile-bundle.md` §6 同步：摘要五行→六行；示例 `25 rows, 30 discovered`；
mcp 行语义（工具名序 / `tools=0` / `approval-required` / 两行移除印 `-`）；
计数沿革 20/25（it22）→ 23/28（it26）→ 25/30（it27）。

### 1.3 HeaderCodec：manifest 结构化 config（`JsonlPersistence`）

背景：`CompositionManifest` 原样进 `SessionHeader` 并落 `header.json`；原 `HeaderCodec`
只接受 String/Number/Boolean，`mcp-stdio` 的 `servers: []`（`List<Map<...>>`）在
**建会话时**即 `not primitive` 失败。扩展为递归 JSON 树：

```java
/**
 * manifest config 值：JSON 树值（原语 / 数组 / 对象，it27 起含结构化行配置如
 * MCP {@code servers[]}），其余 fail loud。数字仍落 {@link JsonValue.Num}(long，
 * 与事件词表一致)；嵌套 null 以 {@link JsonValue.Null} 表示（仅嵌套——Row 顶层
 * 拒绝 null，见 {@code CompositionManifest.Row}）。
 */
private static JsonValue configValue(String plugin, String key, Object value) {
    if (value instanceof String text) {
        return new JsonValue.Str(text);
    }
    if (value instanceof Number number) {
        return new JsonValue.Num(number.longValue());
    }
    if (value instanceof Boolean bool) {
        return new JsonValue.Bool(bool);
    }
    if (value == null) {
        return JsonValue.Null.INSTANCE;
    }
    if (value instanceof java.util.List<?> list) {
        java.util.List<JsonValue> items = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            items.add(configValue(plugin, key, item));
        }
        return new JsonValue.Arr(items);
    }
    if (value instanceof java.util.Map<?, ?> map) {
        JsonValue.Builder object = JsonValue.object();
        for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String field)) {
                throw new IllegalStateException("manifest config '" + key + "' for '" + plugin
                    + "' has non-string map key: " + entry.getKey());
            }
            object.set(field, configValue(plugin, key + "." + field, entry.getValue()));
        }
        return object.build();
    }
    throw new IllegalStateException("manifest config '" + key + "' for '" + plugin
        + "' not a JSON value: " + value.getClass().getSimpleName());
}
```

读侧 `plainValue` 对应新增 `Null` / `Arr` / `Obj` 分支；`Arr`/`Obj` 用
`Collections.unmodifiableList/Map`（保留嵌套 null 与配置序；`List.copyOf`/`Map.copyOf`
会拒绝 null）。返回值与原始 `Map`/`List` 的 `equals` 成立（回归用例断言整份 manifest 相等）。

### 1.4 两处新测试

`JsonlPersistenceTest#manifestConfigRoundTripsNestedValues`：构造
`servers=[{id,args:[a,b],env:{TOKEN:x}}] + enabled:true` 的 manifest → 落 `header.json`
→ 断言树形片段（`"servers":[{`、`"args":["a","b"]`、`"env":{"TOKEN":"x"}`、
`"enabled":true`；不钉 map 键序，`Map.copyOf` 不承诺）→ `load()` 后
`loaded.header().manifest()` 与原 manifest 相等。

`HeadlessVerifyTest#verifyWithFixtureServerNamesMcpToolsOutsideExemptList`：写启用
`mcp-stdio` 行的 profile（`replace: true`，`args` 指向测试资源里的单文件 server，
`java VerifyFixture.java` 直跑），断言：

```text
  mcp: mcp-stdio + mcp-tool; tools=mcp__verify-fixture__echo (approval-required)
  approval: AUTO (approval-auto; exempt=ask_user)
```

且 `stderr` 为空（零噪声）。既有 verify 用例补断言
`"  mcp: mcp-stdio + mcp-tool; tools=0"`。

### 1.5 夹具富 schema（仅测试资源）

`mcp/stdio/src/test/resources/mcp-fixture/FixtureServer.java` 增 `--rich-schema`：
echo 的 `inputSchema` 覆盖 string/integer/number/boolean/array/object + `required`；
未带旗标时行为不变。用于 S2 的 jlink 真跑（jlink 镜像里没有测试类，夹具以源码直跑）。

### 1.6 行集变化消费面

| 面 | 落点 |
|---|---|
| `dist/jh/pom.xml` | jlink modules += `io.javanatic.harness.mcp` / `.mcp.stdio` / `.mcp.tool` |
| `examples/headless/pom.xml` | compile 依赖 += `harness-mcp-mcp` / `harness-mcp-stdio` / `harness-mcp-tool` |
| `integration/consumer-sample/pom.xml` | `candidate-closure` += 同三坐标（`${harness.version}`） |
| `docs/embedding.md` §2 | 基础 15 条 + bundle-base = 16 条不变；候选闭包另记 skill 3 + mcp 3 坐标 |
| 内部第五处 | `bundle/base/pom.xml` 测试闭包 + `AppBootTest` 发现清单 |

新增 `mcp/stdio/src/main/resources/META-INF/services/io.javanatic.harness.kernel.plugin.Plugin`
（内容 `io.javanatic.harness.mcp.stdio.McpStdioPlugin`）：补 classpath 发现通道，
与 `module-info` 的 `provides` 并存。

## 2. 三行映射

| 设计增量条目 | 实现 | 测试/实证 |
|---|---|---|
| 5. 装配与配置：base 两行、缺省 `servers: []` 零噪声 | `bundle.yml` + `bundle/base/pom.xml` + services 文件 | `AppBootTest`（31 例绿）；S2 默认 verify `tools=0`、pgrep 无进程 |
| 5. 行配置 `servers[]` 可持久化（manifest 进 header） | `HeaderCodec` 递归 JSON 树 | `manifestConfigRoundTripsNestedValues`；HeaderCodec 突变检查（List 分支破坏→1 error→还原→1 pass） |
| 5. `--verify` 治理断言：两行在场、MCP 工具全数非豁免 | `HeadlessMain.mcpText` + 07 §6 | 默认用例 `tools=0`；启用夹具用例点名工具且 `exempt` 仍仅 `ask_user` |
| 5. 行集变化四处消费面同步 | 四个 pom/文档 + 内部第五处 | consumer candidate 腿 EXIT=0；jlink 镜像构建绿；headless 82 例绿 |
| 验收：夹具富 schema 真跑 | `FixtureServer --rich-schema`（测试资源） | S2：模型请求面全量映射（integer→number 登记弱化）、日志 `mcp/catalog` + `tool/call` + `tool/result`、子进程回收 |
| 验收：R1 端到端回放 | 无新实现（消费停 2 的 catalog 账本） | `foldRebuildsRegisteredSurfaceFromLedgerOnly` + `catalogEventRoundTripsThroughJsonl`；mcp/tool 26 例绿（7/8/11） |

## 3. 偏离清单

| # | 出现什么 | 为什么 | 待裁决 |
|---|---|---|---|
| ① | **扩 `HeaderCodec`**：原契约「config 值只允许原语」，现接受 JSON 树（原语/数组/对象，嵌套 null）；非 JSON 类型仍 fail loud | `CompositionManifest` 必须整份进 `SessionHeader`；`mcp-stdio.servers[]` 是结构化行配置，不扩即**建会话必失败**（装配期无法启用 MCP）。备选「持久化时剔除 servers」会破坏 header 回放等价，且让会话日志不能自证组合 | 请裁决：接受递归 JSON 树（当前）还是另开持久化投影口径 |
| ② | **jlink 模块名订正**：预期 `io.javanatic.harness.mcp.mcp`，实为 `io.javanatic.harness.mcp`（artifactId ≠ JPMS 名） | 镜像构建首跑 `Module ... not found`；改 `dist/jh` 一条后 SUCCESS。事实性错误，非设计变更 | 知悉即可；已记入证据 |
| ③ | **夹具新增 `--rich-schema`**（仅 `src/test/resources`） | jlink 真跑需要非空富 schema 才能证明映射白名单端到端；不引入测试类到镜像，不改默认行为 | 知悉即可 |
| ④ | **`docs/embedding.md` 计数口径自查订正**：初版误把 mcp 三坐标计入基础依赖（15→18）；现保持基础 15 + bundle-base = 16，候选闭包另记 6 坐标 | 三坐标只在 `candidate-closure` profile，基础块计入会与发布腿事实不符 | 知悉即可 |
| ⑤ | 集成缺陷三处（非设计偏离，已在证据逐条留痕）：classpath 缺 services 文件；manifest 结构化 config 不可持久化；jlink 模块名 | 均由装配/镜像首跑红暴露；修复已含本停改动 | 知悉即可 |

## 4. 形状论证

- **为什么两行缺省在场、`servers: []` 而不是条件行**：组合事实自述优先——
  `--verify` 能看到两行、`tools=0` 能证明零噪声；条件行会让默认组合少两行，
  启用时再改变发现面，`AppBootTest` 与消费方的双向校验都更难解释。空列表是
  「在场但无事可做」，与 `disabled` 的语义区分明确（S2 两行 disabled 印 `mcp: -`）。
- **为什么摘要读 manifest 行 + ToolRegistry 名称，而不是插件自报**：与 approval
  行同一口径——摘要只说组合里的事实；插件若没装，行仍在、`tools=0` 如实暴露。
  两行被自定义 profile 移除时不猜、印 `-`。
- **为什么 HeaderCodec 递归而不是字符串化**：header 是人可读事实源；字符串化会
  让 `load().header().manifest()` 与原 manifest 不再相等，会话回放失去组合自证。
  递归只接受 JSON 可表达类型，越界仍 fail loud；数字沿用 `Num(long)` 词表。
- **为什么补 services 文件而不是改 boot 发现**：classpath 与 module-path 是既有
  双通道契约，`PluginLoader` 的路径不该为单个插件特判；插件自带两种发现描述
  即可，consumer-sample 的 classpath 腿也同时被验证。
- **若错靠什么发现**：行漏同步→`AppBootTest`/consumer candidate 红；
  verify 文案漂移→`HeadlessVerifyTest` 红；HeaderCodec 回退→round-trip 用例红；
  子进程泄漏→S2 的 `kill -0` / pgrep 证据；schema 映射丢字段→真跑捕获的请求体
  与 `mcp/catalog` 原文对不上。

## 5. 验证与证据（已落盘）

- [stop3-s1-assembly.txt](stop3-s1-assembly.txt)：改动面、两个集成缺陷与修复、
  HeaderCodec 突变检查、三模块计数（32/31/82；mcp 35/26）、`validate EXIT=0`
- [stop3-s2-jlink-realrun.txt](stop3-s2-jlink-realrun.txt)：jlink 镜像构建 SUCCESS；
  默认 verify 25/30 `tools=0`；rich verify 四工具 + `PROCESS_REAPED`；两行 disabled
  → 23 rows、`mcp: -`；装配失败 fail loud；富 schema 端到端真跑（catalog/tool call/result、
  integer→number、子进程回收）；真实会话 resume 零联网零进程
- [stop3-s3-r1-and-closure.txt](stop3-s3-r1-and-closure.txt)：
  `verify-consumer.sh candidate` EXIT=0（四负例全 ok）；全量 `mvn -B test` EXIT=0
  （35 模块 692 tests / 0 failures / 0 errors / 23 skipped）；`mvn -B -q validate`
  EXIT=0；四处消费面复核表

## 6. 停等

本停无 commit。请放行后进 it27 收口（#22）：勾验收取证、落 02/03/05/07/12 与
AGENTS.md 同步、评审三备注 ①②③ 与六项偏离中「收口落」的同步面一次性收口，
再提交（不 push）。
