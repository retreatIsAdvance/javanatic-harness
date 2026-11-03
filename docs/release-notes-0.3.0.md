# javanatic-harness 0.3.0

> 本文件 = `v0.3.0` GitHub Release 正文**采用稿**（评审可改）；发布执行（版本翻转 / 签名 / 上传 / tag / Publish）属用户侧，见 [release.md](release.md) §0。

Plugin-based agent harness on the JVM — **Java 25 LTS / JPMS / Maven**. Ports the engineering ideas of DeepSeek Harness (dsh) to the Java ecosystem: **ideas carry over, shapes do not**.

0.3.0 的主题是**工具生态**：Skills（it26）、MCP（it27 / it27.1）、Web 资料获取（it28）、Java LSP（it29）四站全落。全部新面走既有装配与审计通道——零新会话事件（外部内容只作为 `tool/result` 入账，回放零联网零进程）。含**一处破坏性变更**（`ValueSchema.Opt` 新 sealed 变体）——0.2.0 用户升级前先读「升级必读」。

## 升级必读（0.2.0 → 0.3.0）

### 破坏性变更

| 面 | 0.2.0 | 0.3.0 | 动作 |
|---|---|---|---|
| `tools` 包 sealed 接口 `ValueSchema` | permits `Object / Str / Num / Bool / Arr` | 增 `Opt` 变体（可选字段：缺失放行、在场按内层校验；wire 侧零变化——解包直写内层 schema） | 穷尽 switch 的消费者补 `case ValueSchema.Opt`（`opt.schema()` 解包按内层校验或显式跳过） |

其余全部为纯增量：14 个新导出包随 14 个新模块入场（skill 3 / mcp 4 / web 4 / lsp 3）；既有模块导出面、`SessionEvent` sealed permits、CLI flag 面、既有插件 config 键**零变化**（差异审计见 [audit-stability-surface.txt](plan/evidence/release-0.3.0/audit-stability-surface.txt)）。稳定面语义以 [12-api-stability.md](design/12-api-stability.md) 为准。

### 纯增量（不破坏 0.2.0 面）

- **新公开方法**：`ToolArgs.rawJson()` / `readOptionalLong(String)`（实参原文与可选数值读取）；`ConfigValues.masked(Map)` / `isSensitiveKey(String)`（脱敏唯一词表单源，dump 与会话 header 写入共用）。
- **组合自述**：base bundle 新增 14 行插件（37 行 = 24 可配 + 13 无配）；`--verify` 摘要增 `mcp:` / `web:` / `lsp:` 行。
- **新扩展事件**：`skill/catalog`、`mcp/catalog`（各代全量快照，日志顺序即代际；`ExtensionEvent` + `SessionEventCodec` SPI 通道不变）。

## Highlights

- **Skills（it26）**：技能 = 目录 + 提示段 + 工具；四根发现（项目 / 用户 × `.harness` / `.agents`，近者胜）、`cwd` 工作区单源、轮首目录装载 + Dynamic 目录段、`skill` 装载工具（免审批）。缺省组合零技能零噪声。
- **MCP stdio（it27）**：`mcp-registry` + `mcp-stdio` + `mcp-tool`——server 清单配置（装配期启动、失败 fail loud 并回滚）、缺省 `servers: []` 零进程零网络、`mcp/catalog` 扩展事件、工具名 `mcp__<server>__<raw>` 确定性归一、结果恒需审批、回放零联网。
- **MCP 硬化（it27.1）**：Streamable HTTP transport（`mcp-http`，`Redirect.NEVER`、拒敏感 query 参数名、有限重连）可与 stdio 并存；注册表换代（`catalogGeneration()`、消费面候选预检失败旧代保活、轮屏障换代——`PRE_STEP` 落账、被拒轮不落）；结果 256 KiB 上限（`maxResultBytes`，UTF-8 完整边界截断）；脱敏词表单源化（增 `auth` / `cookie`）。
- **Web 资料获取（it28）**：`web/fetch-http` 钉地址公网抓取（仅 https、仅公网地址、一次解析钉地址、逐跳复验、无凭据转发、2 MiB 上限）+ `web/search-deepseek` 原生搜索（keyless 行 disabled）+ `web/tool` 消费面（`web_fetch` 恒注册 / `web_search` 双态、HTML 提取、分层截断、信任前缀 `EXTERNAL_WEB_CONTENT_NOTICE`、9 个稳定错误码）。**零新会话事件**——外部内容只作为 `tool/result` 入账，回放零联网。
- **Java LSP（it29）**：`lsp/lsp` 三能力同步观察契约（诊断 / 定义 / 引用，`Observed` / `NotReady` / `Stale` / `TimedOut` 四态）+ `lsp/jdtls` 长驻 JDT LS 1.63.0 Provider（Content-Length 字节帧自有 wire、进程所有权、四态观察，真实 LS 生命周期取证）+ `lsp/tool` 三工具 Consumer（双态零工具、四态逐字措辞、双帽截断）。观察 hash 锚定拉取时点——空结果只称「拉取时点无诊断」，不称「项目无错误」。
- **Windows 双腿修复（it29 收口后）**：JDT LS 平台分支纯函数化（`java.exe` 选择可单测可突变）+ 5 个 POSIX 专属用例 assumption 门控 + 信号退出码平台映射；CI 四平台全绿（run 41559844431）。

## 平台支持（0.3.0）

| 平台 | 沙箱后端（链） | 强制强度 | 前提 |
|---|---|---|---|
| macOS | seatbelt | **FULL** | Apple Silicon（aarch64 归档；Intel Mac 暂无发行面） |
| Linux | bwrap → landlock | **FULL** | 链取第一个可用后端：bwrap 在 PATH 优先；否则归档自带 landlock 助手（内核 ABI ≥ 3）；都不可用 fail closed（`--verify` 点名原因与出路） |
| Windows | windows-acl | **PARTIAL**（覆盖见「已知残余」） | **PowerShell 7+（`pwsh`）必需**——Windows PowerShell 5.1 不支持；pwsh 缺席时 shell 执行期 fail-closed 点名 |

## Get it

Maven Central — `io.github.retreatisadvance:harness-*:0.3.0`（索引收录可能滞后于本 Release）：

```xml
<dependency>
  <groupId>io.github.retreatisadvance</groupId>
  <artifactId>harness-kernel-core</artifactId>
  <version>0.3.0</version>
</dependency>
```

预构建归档见下方 Assets（运行时装好，解压即用；全平台归档由 CI 产出）：

- `javanatic-harness-0.3.0-macos-aarch64.tar.gz` / `.zip`
- `javanatic-harness-0.3.0-linux-amd64.tar.gz` / `.zip`
- `javanatic-harness-0.3.0-windows-amd64.tar.gz` / `.zip`
- `javanatic-harness-0.3.0-windows-aarch64.tar.gz` / `.zip`

验收：`bin/jh --help` 与 `bin/jh --verify` 均 exit 0（无 key 可跑；Windows 归档启动脚本为 `bin/jh.bat`，且需 `pwsh` 在场——`--verify` 会如实打印沙箱落点）。

```sh
tar xzf javanatic-harness-0.3.0-linux-amd64.tar.gz
cd javanatic-harness-0.3.0-linux-amd64     # 顶层目录同名
bin/jh --help                              # 全部旗标与示例
bin/jh --verify                            # 组合 + 治理断言 + 沙箱落点行（含 mcp:/web:/lsp: 摘要）
DEEPSEEK_API_KEY=sk-... bin/jh --workspace=<existing-dir> "task text"
```

## 已知残余（如实登记）

1. **windows-acl 为 PARTIAL**（不变，两洞在案，[05 §6](design/05-capability-seam.md)）：① 可写面 = 主机上一切 Low 完整性标签对象（不限于 workspace）；② 递归打标跟随 NTFS 硬链接——硬链接别名可越出预期面。
2. **landlock 下 `/dev/null` 写入按设计拒绝**（不变，D4 终裁 A；真机实测 DENIED）——`> /dev/null` 之类重定向会被判拒。
3. **网络策略不在沙箱词表内**（不变）：同机与容器面默认均有网络出口；MCP / Web 的 egress 治理 = 审批 + 审计 + 公网闸（Web 抓取 B17：挂载行即网络授权，出口不经沙箱）；docker Provider 无 `--cpus` / `--memory` 限额（挂账）。
4. **MCP 首版范围（B12）**：不做 Resources / Prompts / Sampling / Roots / Elicitation。
5. **Web 搜索辅助 token 不进 agent 预算账（B-WEB-SEARCH-BUDGET，it28 T9）**：预算只计模型轮内 token；搜索辅助调用受 `maxTokens` / `maxUses` 上限约束。
6. **MCP 工具名归一算法是事实**（it27）：`mcp__<server>__<raw>` 的清洗 + `_hash8` 退路是算法事实，算法变更须按事件 schema 迁移重裁（B13 挂账），不得静默改读。
7. **LSP 观察 hash 不证明 LS 分析来源或完成**：hash 锚定 JH 拉取时点；空诊断只称「拉取时点无诊断」。LS 以用户全权进程运行、不入沙箱——工具治理不构成进程隔离（B23–B25）。协议 / 尾流偏离、写盘 / 下载、主动宿主 JDK 发现等 LSP 边界原样留存。
8. **macOS Gatekeeper 拦截**（不变）：浏览器下载的归档带隔离属性（quarantine），未签名二进制的首跑会被系统拦下；规避二选一——`curl | tar xz` 方式下载（无 quarantine），或 `xattr -dr com.apple.quarantine <解压目录>` 后再运行。
9. **musl / Alpine 不支持**（不变）：归档按 glibc 目标构建（jlink 平台绑定），Alpine 等 musl 发行版不在发行面内。
10. **bwrap 不在 PATH 的宿主形态**（不变，如 NixOS / 精简镜像）链落 landlock；两者都不可用则 fail closed。

## Docs

- [README](https://github.com/retreatIsAdvance/javanatic-harness#readme)（[中文](https://github.com/retreatIsAdvance/javanatic-harness/blob/master/README.zh-CN.md)）
- [docs/embedding.md](https://github.com/retreatIsAdvance/javanatic-harness/blob/master/docs/embedding.md) — 接入指南（Maven 坐标 / profile-bundle / 版本面对照）
- [docs/design/12-api-stability.md](https://github.com/retreatIsAdvance/javanatic-harness/blob/master/docs/design/12-api-stability.md) — 稳定面冻结与 0.3.0 迁移注记
- [docs/release.md](https://github.com/retreatIsAdvance/javanatic-harness/blob/master/docs/release.md) — 发布流程与校验点
