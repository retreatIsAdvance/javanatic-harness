# it27.1 停 3 review packet：消费面双屏障换代 + 词表单源 + 装配/同步 + 收口真跑

> **TL;DR（5 行）**
> 1. **不变量增量**：MCP 清单只在 `TURN_STOPPING` / `PRE_STEP` 换代（轮内 list_changed 绝不当场改面）；预检先于任何注册面改动，换代失败旧代保活；`mcp/catalog` 每代全量快照、无 generation 字段、日志序即代；结果 256 KiB 在 UTF-8 完整边界截断；`auth`/`cookie` 与敏感 query 判定共用 `ConfigValues.isSensitiveKey` 唯一词表。
> 2. **裁决点**：① 账在 `PRE_STEP` 且仅在裁决 `Enter` 后落（拒绝轮不采纳不落账）；② 空清单退役也落账、首次全空不落账；③ URL query 参数名先 percent-decode 再判；④ `mcp/http` 补 `META-INF/services`（停 2 缺口，classpath 消费必需）；⑤ `--verify` 计数固定为 27 rows / 32 discovered（headless patch 后），base 行集 32 = 19 可配 + 13 无配。
> 3. **风险自报**：list_changed 真跑靠 0.35s 延迟消竞态（屏障语义本身由测试矩阵钉住，非真跑证明）；HTTP transport 的 server 是外部进程，真跑只能证 DELETE 收尾、不能证子进程回收；模型在环 runbook 仍缺 `DEEPSEEK_API_KEY`，未伪造；本停点未提交。
> 4. **证据入口**：`stop3-swap-and-ledger.txt`（屏障/账本/截断/缓存 + 突变）、`stop3-cap-and-masking.txt`（词表/URL/头/脱敏）、`stop3-assembly-and-verify.txt`（行集/同步面/verify）、`stop3-jlink-realrun.txt`（双 transport 两代真跑 + R1）、`stop3-closeout-package.txt`（全量 package 688 例）、`stop3-closeout-consumer.txt`（candidate 腿 + 四负例）。
> 5. **若只能改一处**：看 `McpToolPlugin.Surface.adopt`——`precheckForeignCollisions(next)` 必须在 `swap(next)` 之前，且失败路径 `cache.keySet().retainAll(keys)` + 抛 `McpCatalogSwapException`（旧代不摘）；这一处错，双屏障与失败保活同时失守。

- 基线：`6b3b0bb`（it27.1 停 2，已提交）；本停点全部改动**未提交**
- 范围：`mcp/tool` 消费面（双屏障/预检/账本/截断/缓存）+ `kernel/config` 词表 +
  `mcp/http` URL query 校验与 service 文件 + `bundle/base` 四行 + 四处消费面 +
  `--verify` + jlink 双 transport 真跑 + R1 回放 + 设计文档/流程文档同步
- 工作树：31 个已跟踪文件修改（+1092/−237），新增 `.agents/`、`docs/plan/current-state.md`、
  `docs/plan/boundary-register.md`、本停 5 份取证/包文件、`mcp/http/src/main/resources/`
- 计划锚点：`docs/plan/iteration-27.1.md` 停点表已标「停 3 完成，待放行」

## 1. 交付面

### 1.1 `mcp/tool`：待卸清单消费（核心）

**双屏障（T4 重裁）**

- `TURN_STOPPING`（轮排空、关轮前）：只换代，`appendLedger=false`。
- `PRE_STEP`（admit 之前，waterfall）：先 `args.next()` 让链完成裁决，`Enter` 才
  `adopt(session, true)`——落账先于 `user/message` 与首个 `request/header`。
- 轮内收到 `notifications/tools/list_changed`：连接层自增代际，消费面不读、不改面；
  同轮请求的工具面与 schema 指纹不变。真跑 `seq 2..19`（8 工具）→ `seq 22`（6 工具）
  即此顺序：换代在 turn/end 之后、下一 turn 的 user/message 之前。

**预检先于注册面改动**

- `build()` 纯读 + 缓存，稳定读（`catalogGeneration` 前后一致；变化中重试 3 次后
  `McpCatalogSwapException`），快照内公开名去重。
- `precheckForeignCollisions(next)`：新代公开名与此前**非本代**已注册工具撞名即失败，
  发生在 `swap()` 之前；失败路径裁缓存回旧代键集、抛 `McpCatalogSwapException`，
  旧代句柄不摘、注册面不变；turn 以 Error 收尾，server 保持 CONNECTED，下一屏障重试。
- `swap()`：单线程屏障内摘旧代句柄 → 注册新代（空窗不可观察）。

**账本（T8）**

- `mcp/catalog` 每代全量快照：`(serverId, raw name, publicName, description,
  inputSchema 原文)`；**无 generation 字段**，日志序即代，fold 末值胜。
- 首个非空代落账；与末代相等静默；空清单退役落账；从未有过且仍空不落账（零噪声）。
- 拒绝轮（`PreStepDecision.Reject`）不采纳、不落账，下一 accepted 轮补。
- resume：装配期注册当前实况，账在首个 accepted `PRE_STEP` 对账；不一致即补一代。

**结果上限与缓存**

- 缺省 262144 字节；`maxResultBytes` 行配置覆盖（非正/超 int 范围装配期 fail loud）。
- 字节上限 + UTF-8 完整字符边界；marker ` (output truncated)` 不计入上限；
  success 与 tool error 同待遇（只保留 `isError`）。
- entries 缓存键 `(serverId, rawName, inputSchema)`：归一/schema 警告每键一次；
  `adoptSnapshot` 以当前代键集裁剪（换代/失败均裁），不跨代泄漏。

### 1.2 `kernel/config` + `mcp/http`

- `ConfigValues.SENSITIVE_MARKERS` 扩为
  `apikey|token|secret|password|key|auth|cookie`，`masked()` 与公开
  `isSensitiveKey()` 共用唯一实现（子串、大小写不敏感、宁多掩）。
- 消费面实证：`JsonlPersistenceTest` 的 `mcp-http` header 行
  `Authorization` / `Cookie` / `X-Auth-User` → `****`，`X-Tenant` 原样。
- `McpHttpServerConfig`：URL 仍拒 userinfo/fragment；新增 query 参数名校验——按
  `&` 拆分、percent-decode 后命中 `isSensitiveKey` 即 fail loud（`%61uth` 不绕过），
  坏编码 fail loud。headers 保留头/控制字符/大小写重名校验维持停 2 口径。
- `mcp/http/src/main/resources/META-INF/services/io.javanatic.harness.kernel.plugin.Plugin`
  新增：classpath 消费面 `ServiceLoader` 必需（停 2 只声明了 JPMS `provides`；
  consumer 腿实撞后补）。

### 1.3 装配与同步面

- `bundle/base` 行集 32：新增 `mcp-registry`（无配）、`mcp-http`（`servers: []`）；
  `mcp-stdio` 保持空 servers；`mcp-tool` 由无配转可配（`maxResultBytes: 262144`）。
- 四处消费面：根 `pom.xml`（模块）、`dist/jh`（jlink modules 增 mcp.http）、
  `examples/headless/pom.xml`（依赖）、`integration/consumer-sample` 的
  `candidate-closure` profile（mcp 四坐标）；`docs/embedding.md` 闭包文案同步。
- `examples/headless`：`--verify` 的 mcp 行印四行 + 工具名（名称序）+
  `approval-required`；`HeadlessVerifyTest` 固定 27 rows / 32 discovered / 四行 / 8 工具。
- `bundle/base/AppBootTest`：发现面加入 `mcp-registry`、`mcp-http` 的行 id。

### 1.4 文档与流程

- 设计文档 02/03/05/07/12 同步 it27.1 语义（注册表中心、generation、双屏障、
  无 generation 字段、256 KiB/8 MiB 两层、词表单源、HTTP 配置面、计数 40 模块/44 包）。
- `AGENTS.md`：现状、仓库布局、docs 行、评审停点 packet 落盘与 5 行头要求、
  `.agents/` 决策记录阈值改为 active。
- 新流程件：`.agents/notes/`（README + 6 条 ADR 种子）、`docs/plan/current-state.md`
  （一页现状卡）、`docs/plan/boundary-register.md`（B1–B16 边界台账）。

## 2. 停 3 验收项映射（放行后收口勾选）

| 验收项 | 现状 | 证据 |
|---|---|---|
| HTTP transport 协议面全矩阵 | 绿（53 例） | `stop2-http-focused.txt` + 全量 package |
| 连接注册表两 transport 并存/排序/撞 id | 绿 | `mcp-mcp` 3 例 + 真跑 8 工具 |
| T4 换代：轮内延后/轮末采纳/下一轮落账/失败保活 | 绿（25+13 例） | `stop3-swap-and-ledger.txt` + 真跑 seq 2/22 |
| 结果上限 256 KiB/UTF-8/标记/错误同待遇/配置覆盖 | 绿（36 例） | `stop3-cap-and-masking.txt` + 真跑 262163 |
| 凭据脱敏 + URL/头校验 | 绿（18+15 例） | `stop3-cap-and-masking.txt` |
| 治理不破（恒审批、审计、pipeline） | 绿 | `--verify` approval-required；真跑 tool/call+result |
| 大输出/帧安全（1 MiB 真帧、8 MiB fail loud） | 绿 | `mcp/stdio` 39 例 + `mcp/http` 53 例 |
| 行集四处 + 根 pom + `--verify` 27/32 | 已对 | `stop3-assembly-and-verify.txt` |
| jlink 混合真跑、两代账本、零噪声 | 已取证 | `stop3-jlink-realrun.txt` |
| R1 回放零联网零进程 + fold | fold 单测绿；真跑续跑绿 | `stop3-swap-and-ledger.txt` + realrun §4 |
| 全量 package/validate + consumer candidate | package 绿；consumer 见 §5 | `stop3-closeout-package.txt` |
| 设计文档 02/03/05/07/12 + AGENTS + embedding | 已同步 | 本 packet 基线 diff |
| 模型在环 runbook | **未做**（无 key，不伪造） | 维持计划中的挂账 |

## 3. 偏离与裁决点（请逐条确认或驳回）

1. **`--verify` 计数 27/32，而非 base 32/32**：`headless` 默认 profile 对 base 做
   patch（27 行），discovered 仍 32、unreferenced 0；32 是 base bundle 行集。
   文档与测试均按此口径。
2. **HTTP 通知通道需要 `Mcp-Session-Id`**：真跑首启夹具未带 `--session-id`，客户端
   日志 "issued no Mcp-Session-Id; notification channel disabled" 并降级（按约，
   无会话 id 通知无法归属）。带 session id 重启后 GET 通道建立、list_changed 可观测。
   **未改产品代码**；这是夹具运行参数，写进证据避免误读。
3. **HTTP server 为外部进程**：`mcp-http` 是客户端 transport，不管理远端进程；
   真跑以夹具记录 `initialize/get/DELETE` 证明会话建立与收尾，不像 stdio 一样证
   `PROCESS_REAPED`（不用外部进程假造回收证据）。
4. **空清单退役落账**：有 server 被移除/全部移除时落一代空快照（`removedServerAdopts
   EmptyRetirementLedger` 钉住）；R1 回放只 `/exit` 不 admit，故不追加。二者不矛盾。
5. **缓存键含 schema 原文**：schema 变化即新键（映射可能变化）；旧键在
   `adoptSnapshot`/失败路径被裁掉；不引入 TTL/LRU（代际即失效边界）。
6. **稳定读重试 3 次**：provider 异步发布 generation+tools；前后不一致视为“换代中”，
   失败保旧代，下一屏障重试。3 次是廉价上界，非协议重试。
7. **`mcp/tool` 新增 `requires kernel.config`**：`maxResultBytes` 经 `ConfigValues`
   读取与校验，属于既有配置面，不是新依赖方向（config 无反向依赖）。
8. **`mcp/http` service 文件**：停 2 的 classpath 消费缺口在本停补齐；不影响 JPMS
   路径，consumer 腿的失败面来自它（详见 §5 的负例/判读）。
9. **流程件同批落地**：`.agents/notes`、现状卡、边界表按已采纳建议进本停；
   `.agents/notes/README.md` 是索引，六条 ADR 只记录已裁决事项与推翻对象。

## 4. 风险自报

- **R1（真跑竞态）**：list_changed 的刷新线程与轮末屏障存在理论竞态；真跑用 fake
  模型 tool 后延迟 0.35s 消解，事件序一次通过。屏障正确性不依赖该延迟——由
  `midTurnCatalogChangeDefersSwapUntilTurnStopping` /
  `turnStoppingSwapsSurfaceButLedgerWaitsForNextPreStep` 等矩阵钉住；但“真实网络
  任意时延下都能在轮末前完成刷新”未证明，换代失败会保旧代并在下一轮重试，属设计内降级。
- **R2（计数漂移）**：27/32、32=19+13、40 模块/44 包均为本停口径；后续加行/加模块
  必须同批改 `--verify` 测试与 07/12。证据档均附命令，便于复核。
- **R3（HTTP 外部信任面）**：凭据只允许 headers；URL 敏感 query 已 fail loud；
  但服务端可见的 header 值仍以明文发送（TLS 之外无保护）——这是 transport 语义，
  边界表 B5 已登记。
- **R4（公共名残留风险）**：归一后仍存在极小概率撞名（ADR 0003），预检把撞名
  变成 fail loud 而非静默覆盖；未引入全局保留字表。
- **R5（consumer 范围）**：本停只跑 `candidate` 腿（release 不进 CI，脚本明示）；
  发布面仍冻结在 0.2.0，mcp 四坐标属 0.3.0 候选闭包。
- **R6（未提交）**：工作树含全部交付与证据；未 commit、未 push，放行后才进提交批。

## 5. 收口命令与结果

```text
$ mvn -B package
BUILD SUCCESS；37 个测试模块 / 688 例 / 0 失败（详见 stop3-closeout-package.txt）

$ mvn -B -q validate
EXIT=0

$ integration/verify-consumer.sh candidate
候选腿：install BUILD SUCCESS → 示例编译/类路径/治理自证全 ok（SELF-CHECK OK，
tool.schemas=12，word_count 可见可执行）；四负例全部按预期拒绝；
失败项 0，EXIT=0（详见 stop3-closeout-consumer.txt）
```

jlink 真跑（摘要）：

```text
默认 --verify：27 rows, 32 discovered；mcp 四行；tools=0；零进程
rich --verify：8 工具（local 4 + remote 4）；stdio PROCESS_REAPED；HTTP DELETE
三轮真跑：mcp/catalog 8 → 6（无 generation 字段）；第 3 轮零追加；
         big 结果 262163 字节（262144 + marker）
R1：resume EXIT=0；无进程/无联网；日志仅 +end-seed/exit
```

## 6. 请求

放行后建议动作：把本停点作为**一个提交**落地（不 push），同时勾选停 3 验收项并
更新停点表为「已提交」。若需拆分，建议拆两笔：① `mcp/tool` + `kernel/config` +
`mcp/http` 代码与测试；② 装配/同步面 + 文档/流程件 + 证据包。未获点名不放行 push。
