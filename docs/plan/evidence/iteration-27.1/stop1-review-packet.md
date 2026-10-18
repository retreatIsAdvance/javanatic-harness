# it27.1 停 1 review packet：承载契约 + 连接注册表 + stdio 换代

- 基线：`2ee5514`（it27 收口，已推送）；本停点工作区**未提交**（按停点要求）
- 范围：`mcp/mcp` 注册表与代际契约、`DRIFTED` 退役 / 异常更名、`mcp/stdio` 通知刷新与重连换代；
  **不 commit、不进停 2**
- 工作树：17 个修改/删除 + 6 个新增（`McpCatalogSwapException` / `McpRegistry` /
  `McpRegistryPlugin` / services 文件 / `McpRegistryPluginTest` / 迭代计划档）+ 本停点
  两份取证档（本 packet 与 `stop1-registry-and-generation.txt`）
- 证据：`docs/plan/evidence/iteration-27.1/stop1-registry-and-generation.txt`

## 1. 承载契约（全文）

### 1.1 `mcp/mcp` `McpClientService`

```java
public interface McpClientService {

    /** 本服务的服务键。 */
    ServiceKey<McpClientService> KEY = new ServiceKey<>("mcp");

    /**
     * 全部已注册连接，按 server id 排序（稳定面）。零 server = 空列表
     * （零连接、零网络、零进程、零事件、零工具）。
     */
    List<McpServerConnection> connections();

    /**
     * 按 server id 取连接；未配置该 id 返回空（配置启停是常态事实，非错误）。
     */
    Optional<McpServerConnection> connection(String serverId);

    /**
     * 注册一条已连接的 server（装配期；transport 提供者调用）。
     *
     * @param connection 连接面（不可为 null）
     * @return 幂等注销凭据（R3）；连接集合随调用方 scope 收口
     * @throws IllegalStateException 同一 server id 已注册（跨 transport 撞 id）
     */
    Disposable register(McpServerConnection connection);
}
```

Javadoc 头（同文件）：注册是装配期动作；server id 全局唯一（跨 transport 撞 id fail loud，
不静默遮蔽）；`connections()` 按 id 排序；连接与子进程生命周期归各提供者 scope（R3），
注销经注册返回的 `Disposable`。消费面只见本接口——传输形态对消费面不可见。

### 1.2 `mcp/mcp` `McpServerConnection`

```java
public interface McpServerConnection {

    String serverId();

    /** 当前连接状态（诊断面；清单换代不改变状态）。 */
    McpServerState state();

    /**
     * 当前已知工具清单（顺序稳定；刷新异步发布，消费面在轮屏障采纳）。
     */
    List<McpToolDescriptor> tools();

    /**
     * 工具清单代际：装配完成时 1；清单内容（名称 / description / schema）变化才
     * 递增（仅顺序变化不算）。消费面比较代际决定是否在轮屏障换代。
     */
    long catalogGeneration();

    McpCallResult call(String toolName, String argumentsJson, AbortSignal signal);
}
```

类 Javadoc 关键段：

```text
清单语义：tools() 是「当前已知清单」，随远端通知 / 重连对账异步变化；
catalogGeneration() 标出代际（内容变化才递增）。消费面只在轮屏障
（TURN_STOPPING / PRE_STEP）采纳新代——轮内注册面稳定，请求 schema 与账本
不错位（R1/T4）。
```

### 1.3 `mcp/mcp` `McpServerState`

```java
public enum McpServerState {
    CONNECTED,     // 会话活跃：initialize 完成、当前清单在案
    RECONNECTING,  // 断线退避重连中；工具面保留当前清单，call 快速失败
    FAILED         // 终态：重连耗尽或提供者已关闭
}
```

`DRIFTED` 已删除（清单变化不是状态）。

### 1.4 `mcp/mcp` `McpCatalogSwapException`（新建）

```java
/**
 * 新工具清单代际无法落地（it27.1，T4 重裁）：例如新代公开名与既有工具撞名、
 * 公开名归一不可表达。旧代原样保活，当前轮以 TurnEndReason.Error 收口
 * （fail loud，不静默少工具）；清单本身不是连接故障，server 不转 FAILED。
 */
public final class McpCatalogSwapException extends McpConnectionException {
    public McpCatalogSwapException(String message) { super(message); }
    public McpCatalogSwapException(String message, Throwable cause) { super(message, cause); }
}
```

`McpConnectionException` 的 `permits` 由 `McpProtocolException, McpTimeoutException,
McpCatalogDriftException` 改为 `McpProtocolException, McpTimeoutException,
McpCatalogSwapException`；`McpCatalogDriftException.java` 删除。

### 1.5 `mcp/mcp` `McpRegistry`（package-private，新建）+ `McpRegistryPlugin`

```java
final class McpRegistry implements McpClientService {

    private final ConcurrentHashMap<String, McpServerConnection> byId = new ConcurrentHashMap<>();

    @Override
    public List<McpServerConnection> connections() {
        List<McpServerConnection> ordered = new ArrayList<>(byId.values());
        ordered.sort(Comparator.comparing(McpServerConnection::serverId));
        return List.copyOf(ordered);
    }

    @Override
    public Optional<McpServerConnection> connection(String serverId) {
        Objects.requireNonNull(serverId, "serverId");
        return Optional.ofNullable(byId.get(serverId));
    }

    @Override
    public Disposable register(McpServerConnection connection) {
        Objects.requireNonNull(connection, "connection");
        String serverId = connection.serverId();
        McpServerConnection previous = byId.putIfAbsent(serverId, connection);
        if (previous != null) {
            throw new IllegalStateException("mcp server already registered: '" + serverId + "'");
        }
        return Disposable.of(() -> byId.remove(serverId, connection));
    }
}
```

```java
public final class McpRegistryPlugin implements Plugin {
    @Override
    public String id() { return "mcp-registry"; }

    @Override
    public void apply(Scope scope) {
        scope.provide(McpClientService.KEY, new McpRegistry());
    }
}
```

`mcp/mcp/module-info.java` 增：

```java
provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.mcp.McpRegistryPlugin;
opens io.javanatic.harness.mcp;   // JUnit 运行时反射实例化同包测试类
```

另补 `src/main/resources/META-INF/services/io.javanatic.harness.kernel.plugin.Plugin`
（内容 `io.javanatic.harness.mcp.McpRegistryPlugin`）——对齐 skill 模块「module-info
provides + services 文件」双通道惯例（见偏离 D3）。

## 2. stdio 适配（变更面全文）

### 2.1 `McpStdioPlugin`：requires 注册表 + 逐条注册 + LIFO 收口

```java
@Override
public Set<String> requires() {
    return Set.of("mcp-registry");
}

@Override
public void apply(Scope scope) {
    McpClientService client = scope.require(McpClientService.KEY);
    List<McpServerConfig> servers = McpServerConfig.parseAll(
        scope.require(ConfigService.KEY).configFor(id()), id());
    List<StdioMcpConnection> started = new ArrayList<>(servers.size());
    // 先挂回收后启动:中途失败时已拉起的部分仍随 scope close 回收,不留孤儿进程
    scope.onClose(() -> started.forEach(StdioMcpConnection::close));
    for (McpServerConfig server : servers) {
        StdioMcpConnection connection = StdioMcpConnection.start(server, backoff);
        if (scope.isClosed()) {
            connection.close();
            throw new IllegalStateException(
                "mcp-stdio: scope closed during assembly; started connection rolled back");
        }
        started.add(connection);
        scope.onClose(client.register(connection)); // LIFO:先摘注册面,再收连接/进程
    }
}
```

`StdioClientService.java` 删除（transport 不再独占 provide）。

### 2.2 `StdioMcpConnection`：快照 / 通知刷新 / 重连换代

快照与代际面（`volatile` 一次读全，工具与描述符同代发布）：

```java
private volatile Catalog catalog = new Catalog(0, List.of(), List.of());

@Override
public List<McpToolDescriptor> tools() { return catalog.descriptors(); }

@Override
public long catalogGeneration() { return catalog.generation(); }

/** 同代发布：工具快照与描述符一次性构建（读侧 volatile 读一次拿全，无撕裂）。 */
private Catalog snapshot(long generation, List<FrozenTool> tools) {
    List<FrozenTool> frozen = List.copyOf(tools);
    return new Catalog(generation, frozen, frozen.stream()
        .map(tool -> new McpToolDescriptor(config.id(), tool.name(), tool.description(),
            tool.schema().toString()))
        .toList());
}
```

通知回调（读线程只置位 + 拉虚拟线程，不阻塞帧读取；重复通知合并）：

```java
private void handleListChanged(JsonRpcPeer source) {
    synchronized (this) {
        if (closed || peer != source || state != McpServerState.CONNECTED) {
            return;
        }
        refreshRequested = true;
        if (refresher != null) {
            return; // 运行中的刷新线程收尾时会再取一次
        }
        refresher = Thread.ofVirtual().name("jh-mcp-" + config.id() + "-refresh")
            .start(this::refreshLoop);
    }
}

private void refreshLoop() {
    while (true) {
        JsonRpcPeer active;
        synchronized (this) {
            if (closed || !refreshRequested) {
                refresher = null;
                return;
            }
            refreshRequested = false;
            active = peer;
        }
        if (active == null || active.isBroken()) {
            continue; // 断线路径的重连握手会以新清单换代
        }
        try {
            adoptRefresh(active, fetchTools(active));
        } catch (McpProtocolException e) {
            log.log(WARNING, "... malformed data; reconnecting: " + e.getMessage());
            active.invalidate(e);
        } catch (McpTimeoutException e) {
            log.log(WARNING, "... timed out; keeping generation " + catalog.generation() ...);
        } catch (McpConnectionException e) {
            log.log(WARNING, "... failed; keeping generation " + catalog.generation() ...);
        }
    }
}

/** 采纳通知刷新结果：仅当仍指向同一 peer 且内容变化才换代（顺序变化不算）。 */
private void adoptRefresh(JsonRpcPeer source, List<FrozenTool> tools) {
    synchronized (this) {
        if (closed || peer != source) {
            return; // 结果已过期（重连换了 peer）
        }
        Catalog current = catalog;
        if (sameCatalog(current.tools(), tools)) {
            return;
        }
        catalog = snapshot(current.generation() + 1, tools);
        log.log(INFO, "catalog generation " + current.generation() + " -> "
            + catalog.generation() + " (" + tools.size() + " tools, list_changed)");
    }
}
```

重连换代（握手无条件取新清单；内容比较决定代际；不再终态化）：

```java
private void reconnectLoop() {
    JsonRpcPeer stale = peer;
    for (int round = 0; round < backoff.length; round++) {
        attempt = round + 1;
        if (!pauseFor(backoff[round])) {
            return; // 已关闭/中断:终止重连(闭合会话由 close() 负责)
        }
        JsonRpcPeer tentative = null;
        try {
            tentative = connectProcess();
            List<FrozenTool> current = handshake(tentative);
            boolean adopt;
            synchronized (this) {
                adopt = !closed && !tentative.isBroken();
                if (adopt) {
                    Catalog previous = catalog;
                    long generation = sameCatalog(previous.tools(), current)
                        ? previous.generation() : previous.generation() + 1;
                    catalog = snapshot(generation, current);
                    peer = tentative;
                    state = McpServerState.CONNECTED;
                    attempt = 0;
                    if (generation != previous.generation()) {
                        log.log(INFO, "catalog generation " + previous.generation()
                            + " -> " + generation + " across reconnect (" + current.size() + " tools)");
                    }
                }
            }
            if (!adopt) {
                tentative.close(); // 锁外回收:close 要 join 读线程,读线程可能正等本锁
                if (closed) {
                    return;
                }
                continue; // 重连期内再次断线:本档作废,下一档继续
            }
            closeStale(stale);
            log.log(INFO, "reconnected (...)");
            return;
        } catch (RuntimeException e) {
            if (tentative != null) {
                tentative.close();
            }
            log.log(DEBUG, "reconnect attempt ... failed: " + e.getMessage());
        }
    }
    closeStale(stale);
    failExhausted();
}
```

`close()`：置 `closed`/`FAILED`、中断重连与刷新线程、关 peer（级联在途、杀进程树），幂等。
`handleBroken()`：仅 `started && peer == brokenPeer && state == CONNECTED` 时转 RECONNECTING。
`call()`：`closed/FAILED` 与 `RECONNECTING` 快速失败；断链窗口还检查 `peer.isBroken()`；
删除 DRIFTED 分支。`handshake()` 去除 expected 对账参数，返回 `tools/list` 快照。
`fetchTools` / `parseTool` / `sameCatalog` / `spawn` 沿用 it27 实现未改。

### 2.3 `JsonRpcPeer`

```java
private final Consumer<JsonRpcPeer> onListChanged;

JsonRpcPeer(Process process, String serverId, Consumer<JsonRpcPeer> onBroken,
            Consumer<JsonRpcPeer> onListChanged) { ... }

/** 外部判定的会话级失败（如清单刷新协议畸形）：按断裂处理（级联 + 重连）。 */
void invalidate(McpConnectionException cause) {
    broke(cause);
}
```

`handleRemoteMessage` 通知分支：

```java
if ("notifications/tools/list_changed".equals(method)) {
    // T4 重裁（it27.1）：清单变化不再是冻结/漂移——交给连接层异步重取并按代发布。
    // 回调异常不得杀死读线程（否则一次坏刷新拖垮整个会话的可观测性）
    try {
        onListChanged.accept(this);
    } catch (RuntimeException e) {
        log.log(WARNING, "mcp server '" + serverId + "' list_changed callback failed: " + e.getMessage());
    }
}
```

### 2.4 夹具（测试资源，生产零影响）

`FixtureServer.java` 新增两个一次性注入旗标：

- `--list-changed-once M csv`：首个 `tools/call` 正常应答后换清单并发
  `notifications/tools/list_changed`；
- `--garble-refresh-once M`：同上触发通知，且随后首个 `tools/list` 返回非 JSON 垃圾；
- `--variant-tools` 注释由「重连漂移」改为「重连换代」（语义不变）。

### 2.5 测试矩阵（最终树全绿）

- `McpRegistryPluginTest`（新，3）：排序与查找；撞 id fail loud 且保留原注册；
  `Disposable.close()` 幂等且只摘自己（同 id 换绑后旧句柄不误摘新连接）。
- `McpStdioRecoveryTest`（重写，6）：崩溃恢复且内容不变代际不变；重连换清单 → gen 2；
  通知刷新 → gen 2 且旧调用面继续可用；刷新畸形 → 断链重连；退避耗尽 → FAILED；
  畸形帧 → 协议级联 + 重建期快速失败 + 恢复后代际不变。
- `McpStdioPluginTest`（10）：全部装配改为 `loadAll([McpRegistryPlugin, McpStdioPlugin])`，
  含两处 fail-loud 断言。
- `McpToolPluginTest`（7）/ `McpCatalogLedgerTest`（8）：删除旧的漂移终态用例（停产路径），
  其余全绿；`Fakes` 适配新契约（`catalogGeneration()=1`、注册去重、排序复制）。

## 3. 三列映射（计划 → 承载 → 证据）

| 计划条目（停 1 范围） | 承载与行为 | 证据（落盘） |
|---|---|---|
| 注册表居中，跨 transport 撞 id fail loud | `McpClientService.register` + `McpRegistry.putIfAbsent` + `McpRegistryPlugin`；stdio 只注册不 provide | `McpRegistryPluginTest` 3 例；突变 MA |
| 清单代际可观察 | `McpServerConnection.catalogGeneration` + `StdioMcpConnection.Catalog` 同代快照 | recovery：崩溃不变 1 / 重连与通知 → 2；突变 MB |
| `DRIFTED` 退役、异常更名 | `McpServerState` 三态；`McpCatalogSwapException`；删除 `throwIfDrifted` | 全模块编译 + tool 26 例绿 |
| `list_changed` 异步刷新、协议畸形断链 | `JsonRpcPeer` 回调 + `handleListChanged`/`refreshLoop`/`adoptRefresh` + `invalidate` | recovery 通知/畸形两例；突变 MC |
| 重连按新清单换代（不终态） | `reconnectLoop` 握手取新清单，内容比较决定 `generation+1` | recovery 重连换清单例 + 崩溃例 |
| 生命周期与注销（R3） | scope `onClose` LIFO：先摘注册面、再收连接/进程；`remove(id, connection)` 条件摘除 | registry 注销例 + plugin 装配 10 例 |
| 删除 stdio 独占服务 | `StdioClientService.java` 删除 | 编译 + 装配测试 |
| `mcp/tool` 编译适配（过渡） | `requires "mcp-registry"`；删除漂移分支与用例 | tool 26 例绿（见偏离 D1） |

## 4. 偏离清单

- **D1（过渡态，计划内）**：停 1 对 `mcp/tool` 只做编译适配——`checkpoint` 仍在每个
  REQUEST 调用、清单不一致仍 fail loud；双屏障换代（`TURN_STOPPING`/`PRE_STEP`）、
  预检先于注册面改动、PRE_STEP 落账、256 KiB 截断、entries 缓存均在停 3。含义：停 1
  树中若轮内刷新落地，下一个 REQUEST 的旧检查点会 fail loud（不是静默）；停 3 改为
  轮内延后。旧漂移用例 `driftEndsTurnWithErrorAndNoCatalog` /
  `driftWinsOverLedgerMismatch` 随 `DRIFTED`/旧异常删除，停 3 以新矩阵重建对应断言。
- **D2（停点边界，已知不一致）**：`bundle/base` 与 `examples/headless` 尚未挂
  `mcp-registry` 行（计划分配给停 3），而 `mcp/mcp` 已 provides 该插件——装载器对
  「已发现未引用」fail loud，两个模块的装配测试在停 3 补行前预期红。已实测代表用例：
  `AppBootTest#bootsBaseProfileAndManifestFlowsIntoHeader` → `EXIT=1`，
  `IllegalStateException: 发现的插件未被任何行引用: mcp-registry`。停 1 的验证口径是
  MCP 三模块聚焦矩阵 + validate（计划取证表口径）；全量 package/consumer 属停 3。
- **D3（补齐惯例）**：`mcp/mcp` 补 `META-INF/services` 插件声明。`module-info provides`
  已足够 JPMS 运行；services 文件对齐 skill 模块先例，兜底 classpath `ServiceLoader`
  场景。无行为变化。
- **D4（实现注记）**：重连采用「先发布新 peer/新代，锁外 `closeStale` 旧 peer」；
  `close()` 中断刷新线程（忽略中断的 `join` 会让关闭挂起）；通知刷新的协议畸形走
  `invalidate → broke → handleBroken`，与断链同一路径（不另造状态）。
- **D5（文档精度）**：`McpServerConnection` 的 `tools()`/类 Javadoc 表述为「异步变化 /
  消费面轮屏障采纳」——transport 层不感知轮，不能在接口文档承诺「仅在轮间变化」。
- **D6（测试夹具）**：新增两个一次性旗标与相应状态字段；一次性 marker 复用既有
  `armOnce` 惯例，生产代码零改动。
- **D7（计划表述口径）**：停点表停 1 标题「承载契约 + 共享词表」按锚点表解释为
  Definition 词表（`McpServerState` / 异常 / `catalogGeneration`）；`kernel/config` 的
  `ConfigValues` 敏感词表在锚点表中归停 3，本次未动，留与 HTTP 配置校验同批（T6 单源化）。

## 5. 论证

1. **为什么注册表在 `mcp/mcp` 而不是任一 transport**：`mcp/mcp` 是零第三方契约件；
   把注册表放这里，stdio 与 HTTP 都只依赖 Definition，消费面只依赖同一个 `KEY`，
   从类型面杜绝「双 transport 各自 provide 同一 key」的 kernel fail（it27 的形态）。
2. **为什么撞 id 用 `putIfAbsent` fail loud**：server id 是工具名与账本条目的命名空间；
   静默覆盖会让先注册 transport 的进程/工具失去引用且审计错位。保留原注册、抛错，
   装配失败可回滚（R3）。
3. **为什么代际只按内容（name/description/schema）而不按顺序**：顺序变化不改变工具
   语义；若也算换代，会制造无意义的账本重写与屏障折返。`sameCatalog` 按名索引比较，
   不依赖远端稳定顺序。
4. **为什么通知回调必须 try/catch**：回调运行在帧读取线程；一次坏刷新（实现缺陷）
   不能杀死读线程，否则整个会话的响应分发全部失效。真正的协议畸形由刷新线程判定后
   走 `invalidate` 断链，语义仍 fail loud。
5. **为什么刷新失败保留旧代**：超时/网络失败不证明清单变化；保留当前可用清单 +
   WARN 是可诊断的降级。只有「响应畸形」表示会话不可信，才升级为断链重连。
6. **为什么重连换代而不是终态**：T4 重裁的核心——会话内清单冻结会让远端正常演进
   （新增工具）变成「请重开会话」；改为握手取新清单、内容比较后按代发布，消费面在
   轮屏障采纳，既保持轮内稳定，又不丢远端能力。
7. **为什么停 1 不动消费面**：把风险分为「transport 契约/注册表/换代来源」与
   「消费面屏障/账本/截断」两层；先以聚焦矩阵钉死 transport 行为，再在停 3 以屏障
   矩阵统一采纳路径，避免一次停点同时改协议层与轮循环。

## 6. 验证摘要

- 聚焦测试 `mvn -B -pl mcp/mcp,mcp/stdio,mcp/tool -am test`：`EXIT=0`；
  mcp/mcp 3 / mcp/stdio 38（recovery 6、call 6、plugin 10、config 16）/ mcp/tool 26。
- 突变三杀（注册去重 / 代际 +1 / 通知触发）：均预期红 `EXIT=1`，还原后最终树复绿；
  三文件还原 shasum 见取证档。
- `mvn -B -q validate`：`EXIT=0`（仅 spotless 内部 Unsafe 警告）。
- 完整命令、输出行与 shasum：`docs/plan/evidence/iteration-27.1/stop1-registry-and-generation.txt`。

## 7. 停点声明

停 1 完成，等放行；**未 commit、未进入停 2**。放行后按计划进入停 2（`mcp/http`
Streamable HTTP + 配置校验 + HTTP 夹具/故障矩阵）。
