# it35 停 1 review packet —— workbench/rpc 契约 + wire + 绑定面

**新增模块自问（it30 裁决）**：本停新增模块 `workbench/rpc`（是）——纯库、非 capability 三角、无 plugin、base bundle 无加行、无 ServiceLoader 双通道；pits「新插件三件套」不适用（逐项：① META-INF/services 双通道——无 plugin、无 ServiceLoader，N/A；② bundle/base test 域 + headless 编译域承载——base bundle 零加行（四确认裁决「零 bundle 行、headless 直依赖按 tui 先例」），N/A；③ headless 钉死面同步——headless 接线在停 2，本停无消费面，N/A）。`dist/jh` moditect 模块清单登记**延至停 2**（headless 开始消费、出现运行时模块路径时登记，it33 pits #38④ 先例）。

**TL;DR（五行）**

- **不变量增量**：① 协议语义与域逻辑分层——`JsonRpcWire` 纯函数 `String handle(String, Handler)`，翻译点唯一（RpcException → 错误对象，code/message/data 原样、id 回显、堆栈不外泄）；② 三方法 face 域错误分档——NoSuchElementException→-32001 / WriterLockException→-32002 / IllegalStateException→-32003 / IOException→-32603，data 仅域码携带；③ tail 游标 = seq 面——客户端带 `lastSeq`，每请求 Follower 开→排空→取增量→关闭环、无共享态，游标超前 → -32602 fail loud；④ 绑定面编译期硬线 loopback（构造面不收 bind 地址）+ 运行时断言 `isLoopbackAddress`，bind 失败 IOException 原样上抛不翻译不吞；⑤ 响应预算超限 → -32004 fail loud 不静默截断。
- **裁决点**：设计未钉 wire 形状与预算注入面 → 实现钉 `String handle(String body, Handler handler)` 纯函数 + `maxResponseBytes` 构造器注入缺省 8 MiB；tail 排空至游标后收集（O(n) 在 JH 规模余量充足，seq 是唯一稳定游标面——裁决设计点 1 落实现）；测试 HTTP 客户端经 `requires static java.net.http`（static = 不进 jlink 镜像，openai-compat 先例）。
- **风险自报**：① 测试夹具 = in-memory fakes（非设计 §6 措辞的「真实 jsonl 后端」），模块图纪律使然——module-info 只挂 API 模块，真实后端保真留停 3 真机 curl 验证补偿；② M2/M4 突变失败形态 = 测试 helper NPE 而非断言不匹配（有效杀突变，形态如实记录，见证据档 §2）；③ wire 层请求级错误 id=null、handler 域错误 id 回显——两分档边界靠测试钉死，无 type-safe 注册表（三方法 switch 硬编码，v1 面就三方法）。
- **证据入口**：[stop1-tdd-evidence.md](stop1-tdd-evidence.md)（43/43 = 13 wire + 23 handler + 7 绑定面全绿；M1–M4 破坏必红→还原逐字节复绿 + 零残留 grep + `git diff --check` 0；并发 10/10 CPU 饱和；夹具订正实录）。
- **若只能改一处**：改 `JsonRpcWire.handle` 的 RpcException 翻译点——它是协议语义的唯一闸门（标准码/域码分档、id 回显、message 不外泄堆栈都在此收口）；WorkbenchRpc 只负责抛域异常，RpcHttpServer 只负责字节搬运，两者可各自漂移而 wire 独担正确性。

## 契约面（公共 API + 错误分档）

```java
// 帧层：纯函数，无状态无 IO
public final class JsonRpcWire {
    @FunctionalInterface public interface Handler { JsonNode call(String method, JsonNode params); }
    public String handle(String body, Handler handler);   // 恒返回合法 JSON 响应(含错误对象)
}
public record RpcRequest(String method, JsonNode params, JsonNode id) {}
public record RpcResponse(JsonNode id, JsonNode result, RpcError error) {}
public record RpcError(int code, String message, JsonNode data) {}   // 双参构造 data=null

// handler：构造器注入 seam + 预算；域错误抛 RpcException
public final class RpcException extends RuntimeException {          // code()/data() 访问器
    public RpcException(int code, String message);                   // data=null
    public RpcException(int code, String message, JsonNode data);
}
public final class WorkbenchRpc implements JsonRpcWire.Handler {
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 8L * 1024 * 1024;
    public WorkbenchRpc(SessionPersistence persistence, SessionTail tail);            // 缺省预算
    public WorkbenchRpc(SessionPersistence persistence, SessionTail tail, long maxResponseBytes); // 须为正
    public JsonNode call(String method, JsonNode params);
}

// 绑定面薄壳：B29 硬线
public final class RpcHttpServer implements AutoCloseable {
    public RpcHttpServer(JsonRpcWire.Handler handler);
    public void start(int port) throws IOException;   // loopback 恒绑;port=0 ephemeral;bind 失败原样上抛
    public InetSocketAddress address();               // 运行时断言面 isLoopbackAddress()
    public void close();                              // stop(0) + executor.shutdown()
}
```

错误分档（JSON-RPC 2.0）：`-32700` parse error / `-32600` invalid request（非对象、batch 数组、id null 通知、缺 method、错 version）/ `-32601` method not found / `-32602` invalid params（max 非正、session 缺失、lastSeq 非法/超前、params 非对象）/ `-32603` Internal error（兜底，堆栈不外泄）；域码 `-32001` SESSION_NOT_FOUND / `-32002` SESSION_LOCKED / `-32003` LOG_CORRUPT / `-32004` RESPONSE_TOO_LARGE。id 回显：number/string 原样；请求级错误 id=null。HTTP 层：非 POST → 405（`Allow: POST`）、POST 非 `/` → 404、`Content-Type: application/json; charset=UTF-8`。

边界声明（Javadoc）：协议语义全部归 wire 层；server 只做 transport 守卫与字节搬运；handler 只抛域异常不构造协议形状；并发请求无共享态（Follower 每请求开→读→关闭环）；零写路径（三方法全读侧，tail 四契约与 load 的正相反）。

## 三行映射（设计增量条目 → 实现 → 测试）

| 设计增量（stop1-seam-design.md） | 实现 | 测试 |
|---|---|---|
| §0 三层形状 + 新模块 workbench/rpc（零 Plugin、零新第三方、Jackson 边界 +1） | JsonRpcWire / WorkbenchRpc / RpcHttpServer + RpcException/词表 record + RpcJson 适配器 + module-info（7 requires + 1 static）+ pom.xml 两处 | 43 用例三测试类（见证据档） |
| §1 方法面三件 sessionsList / sessionViews / sessionTail | WorkbenchRpc 三方法 + RpcJson 视图映射（六组件全字段；arguments 原始 JSON 串透传） | WorkbenchRpcTest 23（摘要映射+截断 total / 五视图 JSON 形状 / tail 游标矩阵 -1·中间·尾部·超前·空增量 / 撕裂尾容忍 / 预算） |
| §2 错误分档（标准码 + 域码 + data 仅域码） | wire `handle` 翻译点 + WorkbenchRpc catch 分档（译码顺序 NoSuch→Locked→Corrupt→IO 兜底） | JsonRpcWireTest 13（id 回显 / -32700 / -32600 矩阵 / -32601 / params 型错矩阵）+ WorkbenchRpcTest 域码断言（M1 守） |
| §3 响应预算 B35 | maxResponseBytes 构造器注入 + `RpcJson.sizeOf` 超限判 -32004 | `oversizedResponseYieldsResponseTooLarge`（M4 守） |
| §4 绑定面 B29（loopback 恒绑、ephemeral、bind 失败上抛、虚拟线程、close） | RpcHttpServer（`InetAddress.getLoopbackAddress()` 编译期硬线 + `address()` 运行时断言面） | RpcHttpServerTest 7（loopback 断言 / bind 失败 / 405 / 404 / 三方法端到端 / 并发无共享态 / close；M3 守） |
| tail 游标机制（裁决设计点 1：seq 唯一稳定游标面） | 排空至尾 → timeline(all) → slice(lastSeq+1..) → nextSeq=size-1；clamp 超前 -32602 | tail 矩阵 + 跨批累积（M2 守） |

## 偏离清单

| 出现什么 | 为什么 | 待裁决 |
|---|---|---|
| 测试夹具 = in-memory fakes，非设计 §6「夹具 = 真实 jsonl 后端临时目录」 | 模块图纪律：module-info 只 requires API 模块，测试代码编译期即受约束，真实 jsonl 后端实现类（kernel 侧）不可见 | 无——真实后端保真随停 3 真机 curl 三方法补偿验证 |
| 视图 JSON 预期 2 消息 → 3 消息（两条同形 user） | `DeriveMessage.project` 逐节点派生，夹具同时含 ToolResultEvent 与同块 UserMessageEvent（R1 规则，非投影缺陷） | 无——测试预期订正，映射代码零改动 |
| `requires static java.net.http`（module-info 新增一行） | 绑定面真 HTTP 往返测试需 HttpClient；static = 不进 jlink 镜像（openai-compat 先例） | 无 |
| 增 bind 失败 + close 用例（超设计 §6 列表 T12/T13） | 设计 §4「bind 失败 IOException 上抛不翻译不吞」（裁决设计点 2）与 §0「close() 停止（R3 收口）」已钉契约，测试是契约钉子 | 无 |
| 域码翻译测试落 WorkbenchRpcTest 而非设计 §6 的纯 wire 组（T6） | 翻译横跨两层：handler 抛 RpcException（域码在此生成），wire 原样翻译——测 handler 断言 errorCode 是事实面 | 无——分层词面差异，非契约偏离 |
| M2/M4 突变失败形态 = 测试 helper NPE（非断言不匹配） | clamp/预算摘除后响应无 error 节点，`errorCode()` 直取 `.get("error")` 空指针；仍有效杀突变 | 无——形态如实记录（证据档 §2），不改 helper |

## 为什么是这个形状 / 若错靠什么发现

- **为什么三层 + RpcException 中间层**：纯函数核（wire）可无 I/O 单测，handler 注入 seam 可喂故障矩阵，薄壳只做字节搬运。handler 抛内部 RpcException 而非直接构造错误对象——协议形状（code/message/data/id 回显）只在 wire 出现一次，域面（session id 等 data）由 handler 附上，两层各自可测、翻译点单一。
- **为什么预算判在 handler 层**：预算语义属域面（响应内容多大），注入面在 WorkbenchRpc 构造器（测试可注入小值）；wire 不知内容大小。`sizeOf` 是纯函数，超限抛 -32004 走统一翻译。
- **为什么绑定面构造收 Handler 不收端口**：端口是运行时决策（`--port=` 停 2），bind 地址是编译期硬线（B29「无 --host」的 Java 形状）——构造面即形状，运行时断言面（`address().getAddress().isLoopbackAddress()`）兜 M3 类漂移。
- **若错靠什么发现**：43 用例矩阵 + M1–M4 突变 + 并发 CPU 饱和 10/10 + 停 3 真机（jlink 镜像 curl 三方法、B29 硬线实证、真实 jsonl 后端保真）+ CI 四腿。帧层错误（错码/漏回显/外泄堆栈）在 wire 13 用例即红；域错误错档在 handler 域码断言即红；绑定漂移在 M3 即红。
