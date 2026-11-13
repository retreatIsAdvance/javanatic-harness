# 停 2 设计定稿：--serve CLI 面 + Follower.close 赋义 + 装配/同步面（it35 W3）

停 1 放行携带五项（[stop1-verdict.md](stop1-verdict.md)）：① `--serve` CLI 接线（互斥矩阵 + `--help` + 12 §6 同步）；② B29 从预登记转正（硬线文本 + 运行时断言 + 突变证据三件引用成正式 B 行）；③ B35 登记；④ moditect 清单 + 四件套自问；⑤ `Follower.close` 赋义（R3 收口语义——tail 资源随服务关闭回收）。本档把五件的契约面、装配面与同步面钉死；实现与测试随 S1/S2/S3（TDD）。

## 1. Follower.close 赋义（seam 契约变更面——承载契约）

现状（it34）：`JsonlFollower` 无持久资源（通道每次 `nextEvents` 即开即关、无锁、字节游标两字段），`close()` 空操作，Javadoc「丢弃游标(无持久资源;…预留的对称形状)」。

**赋义 = 语义终结（fail loud）**，不是虚构 OS 资源释放：

- `close()` 幂等；close 后游标终结——`header()` / `nextEvents()` 再调抛 `IllegalStateException`（关后误用不再是静默成功——今天空操作下关后还能继续读，Javadoc「丢弃游标」承诺未被兑现）。
- R3 收口形状成立：`RpcHttpServer.close()` → 请求环 try-with-resources → `follower.close()` 是真动作（游标终结）；未来长驻消费面（推送/轮询线程）出现时，close 即成真释放的挂点。
- 同步面：seam Javadoc（`SessionTail.Follower.close` 条款改写 + `open` @return 补「close 后游标终结(再读 fail loud)」）+ `JsonlPersistence` 类 Javadoc（L60 四契约提法不动——close 是方法契约不是第 5 条读面契约）+ 02 模块表 SessionTail 行不动（四契约口径不变）。
- 消费面回归：rpc `WorkbenchRpc`（try-with-resources 只关一次，无后读）、tui `workbenchSource`（close 在循环尾，无后读）、rpc 测试 fake（`RpcFixtures.FakeTail` 镜像契约——假实现同样关后拒读）。

## 2. CLI 面（--serve / --port）

- **词表**：`jh --serve [--port=<N>]`。`--serve` 独立 flag；`--port` 1-65535 整数（缺省 **8787**，停 1 已钉；port=0 是测试/取证面，CLI 不收）。`--port` 需与 `--serve` 同用（`--image`/`--docker` 同款）。
- **互斥矩阵（exit 2，同 --workbench 口径，四确认 L9）**：与任务文本 / `--resume` / `--verify` / `--sessions` / `--session` / `--session-view` / `--workbench` 互斥——一次只做一件事。`--follow` 经「需与 --workbench 同用」传导拦截，不单列。
- **行为**：AppBoot boot（keyless，读侧旁路——dispatch 在 API key 检查**之前**，与 `--session`/`--workbench`/`--sessions` 同段）→ `root.require(SessionPersistence.KEY)` + `SessionTail.KEY` → `new WorkbenchRpc(persistence, tail)` → `RpcHttpServer.start(port)` → stdout 一行 `JSON-RPC server 127.0.0.1:<port>（只读工作台面；Ctrl-C 退出）` → 阻塞至 SIGINT。
- **停机（SIGINT）**：首按 Ctrl-C → `server.close()` + 解除等待、exit 0；再按 → `halt(130)` 强退（既有口径）。裁决抽 `ServeShutdown` 小类（`Runnable close` + `IntConsumer halt` 注入，`onSignal()` 同步可测——仿 `SigintPolicy` 先例；`bindSigint` 胶水同款，SIGINT 不可用则 WARN 直杀）。
- **端口占用失败面（停 1 设计点 2 落实现）**：bind 失败 `IOException` 原样上抛不翻译不吞（RpcHttpServer 契约）→ CLI 侧 catch → stderr `监听 127.0.0.1:<port> 失败（端口被占用）: <msg>；换 --port=<N> 或结束占用进程后重试` → **exit 3**（写者锁冲突同族，勿新造退出码）。
- **缺省组合零监听**：socket 只在 `--serve` 路径创建（结构不变量——其余旁路与任务路径零 `RpcHttpServer` 引用）。
- **usage**：USAGE 文本加两段（用法段一行 + flags 段两行，见 S2 实现）；12 §6 冻结项列表 + 语义句同步。

## 3. 装配面（it34 tui 先例）

- `examples/headless`：pom + `harness-workbench-rpc` 依赖；module-info `requires io.javanatic.harness.workbench.rpc` + `requires static jdk.httpserver` → **`requires jdk.httpserver`**（测试面转真运行时消费；static = 不进 jlink 镜像，非 static 后全图解析自动带入）。
- `dist/jh` moditect 清单 **+2 行**：`io.javanatic.harness.workbench.rpc` + `jdk.httpserver`（it16 出链回补，四确认 T3：镜像 67→68 + `bin/jwebserver` 回归，停 3 `--list-modules` 实录）。
- **零 bundle 行、零新配置键**（四确认裁决）——`--verify` 计数不变（34/41/0，S3 取证）；Server 双 seam（SessionPersistence/SessionTail）由 persistence-jsonl 既有行提供，rpc 纯构造注入。
- **四件套自问**：① META-INF/services 双通道——无 plugin、无 ServiceLoader，N/A；② bundle/base test 域 + headless 编译域承载——base 零加行、headless pom/module-info 承载，N/A；③ headless 钉死面同步——12 §6 + USAGE + HeadlessOptionsTest（本停钉死）；④ moditect 清单登记——本停落 +2 行。

## 4. 同步面清单（S3 批）

| 面 | 动作 |
|---|---|
| boundary-register | **B29 转正**（状态列改「正式（it35 W3）」+ 证据三件引用：硬线文本 `RpcHttpServer` 构造面不收 bind 地址 + 运行时断言面 `address().getAddress().isLoopbackAddress()` + 突变 M3 loopback→wildcard 破坏必红；验收硬线 = 停 3 jlink 监听地址实录 + 非回环访问拒绝）；**B35 新行**（响应预算 8 MiB 构造注入、-32004 fail loud 不静默截断、mcp T7 同族；证据 = 实现 + `oversizedResponseYieldsResponseTooLarge` + 突变 M4） |
| notes | `0011-web-ui-non-goal-reversal.md` 新建（T2：背景 / 裁决 / 推翻对象 / 后果 / 证据；顺带承载「ACP 等价物 = 形态类比依据，非控制面授权」措辞解释） |
| 00 §6 | L198「不做 Web UI」改写：本地回环 Web UI 入 0.5 W4（无构建链单文件 vanilla JS，server 直接服务 .html，不引入 node 工具链）；远程/多租户 Web UI 维持不做 |
| README | L88 Out of Scope「Web UI」→「远程/多租户 Web UI」；0.5 路线行核对 |
| 12 §6 | CLI 冻结项 + `--serve` / `--port=` + 语义句（keyless 只读回环 server、互斥面、端口占用 exit 3、缺省 8787）；导出面表 + `workbench/rpc` 行（0.5 增量面，非稳定面） |
| 02 §6 | Jackson 边界清单 8 → 9（+workbench/rpc）+ 模块表 rpc 行；SessionTail 行不动（四契约口径不变） |
| AGENTS/current-state | workbench 现状段 + W3 句；current-state 卡同步（收口批口径随本停） |
| 全局 grep | 「不做 Web UI」旧措辞零残留（T2 改判同步面） |

## 5. 测试矩阵（S1/S2，TDD 红绿 + 突变）

S1（Follower.close 赋义，persistence-jsonl `SessionTailTest`）：close 幂等（双 close 不抛）/ close 后 `nextEvents()` → IllegalStateException / close 后 `header()` → IllegalStateException。突变：guard 摘除 → 必红。消费面回归：rpc/tui 既有 43+ 用例全绿（fake 镜像契约更新后）。

S2（CLI，headless）：`HeadlessOptionsTest` —— `--serve` 缺省端口 8787 / `--port` 解析 + 越界（0 / 65536 / 非数）exit 2 / `--port` 无 `--serve` → exit 2 / 互斥矩阵 8 件（任务文本/resume/verify/sessions/session/session-view/workbench 各 + 组合代表）；`ServeShutdown` 单测（首按 close+countDown、再按 halt(130)）；`runServe` e2e（AppBoot 真 boot + jsonl 临时目录，ephemeral 端口 → 解析 stdout 地址 → HttpClient sessionsList 往返 → close 后请求失败）；端口占用 → exit 3（预占端口再 runServe，词面断言 stderr）。突变：互斥检查摘除 → 必红；close 收口摘除 → e2e 必红；exit 3 词面改码 → 必红。
