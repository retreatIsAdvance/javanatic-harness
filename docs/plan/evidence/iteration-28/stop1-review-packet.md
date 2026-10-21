# it28 停 1 review packet：Web Definition 契约 + 钉地址 HTTPS/1.1 传输

- 基线：`4245700`（it28 四确认与计划落盘）；本停点工作区**未提交**（按停点要求）
- 范围：`web/web`（Definition 契约 + 契约测试）、`web/fetch-http`（钉地址 HTTPS/1.1、
  URL 策略、公网地址闸、重定向逐跳、线路/chunked、内容策略、上限/超时/取消、插件装配）；
  **不 commit、不进停 2**
- 工作树：根 `pom.xml` 修改（`web` 聚合 + 四模块 dependencyManagement，本停点只实装两模块）；
  `web/` 35 个未跟踪文件（3 个 pom + 24 个主源码/资源 + 8 个测试/夹具）；本停点 5 份证据档
  （本 packet + 4 份 `.txt`）
- 零第三方依赖：两个 pom 无新增外部坐标，只用 JDK + 仓内 `kernel` / `kernel.config` /
  `llm.llm` / `web`
- 评审重点（用户预告）：**A. 钉地址传输的 TOCTOU 窗口闭合**；**B. 重定向逐跳校验**

## 0. 五行 TL;DR

1. **契约先行**：`web/web` 落 10 个契约类型——9 码错误词表逐名钉死、`WebFetchBody` 为
   sealed（`Html` / `Text`）、非 2xx 是结果不是错误、取消穿透 `AbortedException`；
   消费面只依赖 `WebFetchService.KEY` / `WebSearchService.KEY`。
2. **TOCTOU 闭合**：每跳解析**恰一次**拿整组地址 → 整组过公网闸 → `Socket.connect`
   直接连**钉住的 `InetAddress`** → 用 URL 主机名在已连通明文 socket 上分层 TLS
   （SNI + endpoint identification 用主机名，TCP 目标不变）；全程不再解析。
3. **重定向逐跳**：301/302/303/307/308 一律按 GET 重发；每跳重跑 URL 策略、重新解析、
   重新过闸、重新钉地址；不转发任何头；拒 https→http、拒超 5 跳、拒坏/缺 Location。
   停点内发现并修正一个真实口径缺口：**302 无 Location 原会当普通结果返回**。
4. **T2 三条件全部落地**：① chunked 解码实现并测试（扩展/trailer/坏帧/超限截断）；
   ② 服务器回 gzip → 显式 `WEB_UNSUPPORTED_CONTENT_TYPE`，不解码不猜测；
   ③ 字面 IP 经 `ofLiteral` 后走**同一** `PublicAddressGate`，多答案任一非公网整组拒。
5. **验证**：`validate` EXIT=0；模块汇总 `web/web` 8 + `web/fetch-http` 113 = **121**；
   钉地址用例 3 次重复 22/22；突变 M1–M8 均“破坏必红 → 还原复绿”，还原逐字节一致；
   残余 2 项（DNS 解析不可硬中断、自定义 NAT64 前缀）交裁。**未 commit，停点表仍 ☐**。

## 1. 承载契约（`web/web`）

### 1.1 模块面

```text
web/pom.xml                         聚合器（本停点只含 web / fetch-http；search/tool 停 2/3 加入）
web/web/pom.xml                     契约模块（requires kernel、llm.llm）
web/web/src/main/java/module-info.java
web/web/src/main/java/io/javanatic/harness/web/
    WebErrorCode / WebException
    WebFetchService / WebFetchRequest / WebFetchResult / WebFetchBody
    WebSearchService / WebSearchRequest / WebSearchResult / WebSearchSource
```

`module-info` 导出并 opens 契约包（opens 仅供 JUnit 反射实例化同包测试类，不改编译期可见性）。

### 1.2 错误词表（9 码，逐名钉死）

| 码 | 语义（Javadoc 摘要） |
|---|---|
| `WEB_INVALID_URL` | URL 解析失败、非 https、含 userinfo、超长或 IDN/百分号非法 |
| `WEB_BLOCKED_ADDRESS` | 主机解析结果或字面 IP 非公网（含多答案中任一非公网） |
| `WEB_REDIRECT_BLOCKED` | 超跳、降级到 http、Location 非法/缺失、相对解析失败 |
| `WEB_UNSUPPORTED_CONTENT_TYPE` | 类型不在白名单、charset 不支持、`Content-Encoding` 非 identity |
| `WEB_TOO_LARGE` | **声明**体长超上限（流式增长超限按截断，不产生本码） |
| `WEB_TIMEOUT` | 总截止时间或连接超时 |
| `WEB_NETWORK_ERROR` | 解析/连接/TLS/HTTP 协议畸形或截断 |
| `WEB_PROVIDER_UNAVAILABLE` | 未挂载 provider，或 provider 缺凭据/配置不可用 |
| `WEB_PROVIDER_ERROR` | provider 端点失败、结果不可解析、未返回原生搜索结果块 |

`WebContractTest` 用 `containsExactly` 钉死这 9 个名字与顺序（防止后续增删悄悄漂移）。

### 1.3 抓取面

```java
public interface WebFetchService {
    ServiceKey<WebFetchService> KEY = new ServiceKey<>("web-fetch");
    String id();
    String displayName();
    boolean available();          // 廉价本地检查，绝不联网
    WebFetchResult fetch(WebFetchRequest request, AbortSignal signal) throws WebException;
}
```

```java
public record WebFetchRequest(String url) {                 // 只保证非 null
    public WebFetchRequest { Objects.requireNonNull(url, "url"); }
}
```

`WebFetchRequest` 的文档明确：URL 的完整安全策略（https、长度、userinfo、IDN、地址闸）
由 Provider 在解析时执行；**空串同样交由 Provider 的 URL 策略拒绝（`WEB_INVALID_URL`）**，
契约层不抛 `IllegalArgumentException`。这样“坏 URL 是 `WebException`”只有一个出口。

```java
public record WebFetchResult(String url, int statusCode, WebFetchBody body, boolean truncated) {
    // url/body 非 null；statusCode ∈ [100, 599]；truncated 是内容事实，不是错误
}
```

```java
public sealed interface WebFetchBody {
    String content();
    record Html(String content) implements WebFetchBody { ... }  // text/html、application/xhtml+xml
    record Text(String content) implements WebFetchBody { ... }  // text/*、JSON、XML、+json、+xml
}
```

sealed 的用意：新增正文种类会让所有消费者编译失败，禁止静默透传。

### 1.4 搜索面（本停点只定契约，停 2 实装 provider）

`WebSearchRequest(query, maxResults)`：query 空白 / maxResults 非正 → `IllegalArgumentException`。
`WebSearchResult(Optional<String> summary, List<WebSearchSource> sources, boolean truncated)`：
`null` fail loud，sources 构造时 `List.copyOf` 不可变副本。
`WebSearchSource(url, Optional title, Optional snippet, Optional publishedAt)`：只有 URL 必填；
`of(...)` 把 null/空白归一为 `Optional.empty()`，不编造来源元数据。

契约测试 8 例覆盖：9 码词表、请求 fail loud、结果状态码界、sealed body、来源归一的
null/空白 → empty、来源列表不可变副本、`WebException` 的 code/cause 穿透。

## 2. 钉地址传输（`web/fetch-http`）

### 2.1 装配与配置

```java
public final class WebFetchHttpPlugin implements Plugin {
    @Override public String id() { return "web-fetch-http"; }

    @Override public void apply(Scope scope) throws Exception {
        FetchLimits limits = FetchLimits.parse(
            scope.require(ConfigService.KEY).configFor(id()), id());
        SSLContext context;
        try {
            context = SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("web-fetch-http: default TLS context unavailable", e);
        }
        PinnedHttpClient client = new PinnedHttpClient(limits, AddressResolver.system(),
            PublicAddressGate.INSTANCE, context);
        scope.provide(WebFetchService.KEY, new PinnedFetchService(client));
    }
}
```

- 装配期**零网络、零解析**；`available()` 恒真（TLS/解析失败是单次调用错误，不是装配期不可用）。
- 配置键白名单 `timeoutSeconds`（默认 30，1..600）与 `maxBodyBytes`（默认 2 MiB，1..64 MiB）；
  未知键、非数字、越界一律 `IllegalStateException` 带插件 id 与键名（fail loud）。
- 同时提供 `META-INF/services/...Plugin` 与 `module-info provides` 双通道（对齐既有模块惯例）。

### 2.2 钉地址路径（核心摘录，`PinnedHttpClient.java`）

```java
private Session open(FetchTarget target, long deadline, AbortSignal signal,
                     AtomicReference<Socket> active) {
    List<InetAddress> addresses = resolve(target, signal);   // 每跳恰一次
    gate.verify(target.host(), addresses);                   // 整组过闸，任一不合格即抛
    signal.checkAbort();
    for (InetAddress address : addresses) {
        Socket plain = new Socket();
        active.set(plain);
        plain.connect(new InetSocketAddress(address, target.port()),
            connectTimeoutMillis(deadline));                  // 直接连钉住的地址
        SSLSocket tls = wrap(plain, target);                  // URL 主机名 + SNI + endpoint ID
        ...
    }
}
```

```java
private SSLSocket wrap(Socket plain, FetchTarget target) throws IOException {
    SSLSocket tls = (SSLSocket) sslContext.getSocketFactory()
        .createSocket(plain, target.host(), target.port(), true);
    SSLParameters parameters = tls.getSSLParameters();
    parameters.setEndpointIdentificationAlgorithm("HTTPS");
    parameters.setApplicationProtocols(new String[] {"http/1.1"});
    if (!target.ipLiteral()) {
        parameters.setServerNames(List.of(new SNIHostName(target.host())));
    }
    tls.setSSLParameters(parameters);
    return tls;
}
```

字面 IP：`resolve()` 返回 `List.of(InetAddress.ofLiteral(target.host()))`，随后**仍然**
`gate.verify(...)`（T2 条件 ③）；不因为来自字面量而跳过闸。

### 2.3 请求面固定头

```text
GET <requestTarget> HTTP/1.1
Host: <hostHeader>
User-Agent: javanatic-harness/0.3
Accept: text/html,application/xhtml+xml,text/plain,application/json,application/xml
Accept-Encoding: identity
Connection: close
```

没有 Cookie、Authorization、Referer、Origin；没有代理、没有 `Authenticator`、没有
`java.net` 进程级默认值读取。用例断言这三个头为 null，并精确断言 `Accept` 值。

### 2.4 URL 策略（`FetchUrlPolicy`）

- 只接受 `https`；拒绝 userinfo、无 scheme、host 百分号、非规范 IPv4（`999.1.1.1`）、
  端口 0/65536/非数字/空、未加括号的 IPv6、空格、反斜杠、超过 2048 字符。
- host 小写、尾点去除；IDN → punycode；路径/查询按 UTF-8 百分号编码，**保留并大写**
  既有 `%XX`；坏 `%` → `WEB_INVALID_URL`，消息固定为 `invalid percent-encoding in url`
  （不回显完整 URL/query）。
- fragment 不进请求目标；缺省 443 不进 Host 头；非默认端口进 `Host`；IPv6 保留方括号
  （`[2001:4860:4860:0:0:0:0:8888]:8443`）。
- 重定向 Location 相对解析以当前 URL 为基准，支持 `../` 与 scheme-relative，结果必须仍是 https。

### 2.5 公网地址闸（`PublicAddressGate`）

整组语义：`verify` 遍历**全部**答案，任一非公网立即抛错（含 host 与命中地址；不回显 URL 其他部分）。

| 组 | 拒绝 | 放行（节选） |
|---|---|---|
| IPv4 | 0/8、10/8、127/8、100.64/10、169.254/16、172.16/12、192.168/16、192.0.0/24、192.0.2/24、192.88.99/24、198.18/15、198.51.100/24、203.0.113/24、224/4、240/4（含广播） | `8.8.8.8`、`1.1.1.1`、`93.184.216.34`、`203.0.114.1`、`198.51.99.1` |
| IPv6 | `::`、`::1`、fe80::/10、fc00::/7、ff00::/8、2001:db8::/32、2002::/16、2001::/23（Teredo 等）、3fff::/20、64:ff9b::/96、64:ff9b:1::/48、`::/96` 兼容段 | `2001:4860:4860::8888`、`2606:4700:4700::1111`、`2a00:1450:4001:80f::200e`、`3ff0::1` |
| IPv4-mapped | `::ffff:127.0.0.1`、`::ffff:10.0.0.1` | `::ffff:8.8.8.8`（按内嵌 IPv4 同判） |

IPv6 的总体口径：只接受 `2000::/3` 全球单播，再从中剔除 Teredo/文档/6to4/3fff 等已知非公网段。

### 2.6 重定向

```java
if (isRedirect(head.statusCode())) {
    if (redirects >= MAX_REDIRECTS) { throw new WebException(WEB_REDIRECT_BLOCKED, ...); }
    redirects++;
    target = FetchUrlPolicy.resolveRedirect(target, location);  // 坏/缺 Location 在此抛
    continue;
}
```

- 301/302/303/307/308 **一律按 GET 重发**（本客户端只有 GET，语义一致）。
- 每跳重跑 `parse/resolveRedirect` → `resolve` → `gate.verify` → 连钉住地址 → 新 TLS；
  不转发任何头（本就没有可转发的头）。
- https→http 降级、坏/缺/空白 Location、`javascript:`、坏方括号、超长目标、超 5 跳
  全部 `WEB_REDIRECT_BLOCKED`。
- 3xx 的正文不读、不做内容判定；连接随 try-with-resources 关闭。

### 2.7 线路与 chunked（T2 条件 ①）

`FetchProtocol` 为手写有界解析器：

- 状态行接受 `HTTP/1.0` / `HTTP/1.1` 的 2xx–5xx（1.0 响应仍按 HTTP 语义有界读取）；
  HTTP/2、ICY、1xx（含 101）、非数字、999、空响应拒。
- 头区：8 KiB/行、100 头、64 KiB head；拒 obs-fold、非法头名、控制字符、无冒号；
  头名大小写不敏感、值去 OWS、重复头保序。
- 帧：CL 与 TE 同时出现拒；TE 只接受 `chunked`；重复 CL 必须一致；
  声明超上限 → `WEB_TOO_LARGE`（不读体）；短体 → 协议错；204/304 不读体。
- chunked：十六进制大小写均可；扩展忽略；0 块后读 trailer 到空行；trailer obs-fold 拒；
  size 行 8 KiB 上限与十六进制溢出拒；块数据后必须严格 CRLF（专用错误消息）；
  超上限停收并 `truncated=true`（恰好到上限不置截断）。
- 端到端：回环 TLS 夹具发 `4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n` → 正文 `Wikipedia`。

### 2.8 内容策略（T2 条件 ②）与上限/超时/取消

- `text/html` / `application/xhtml+xml` → `Html`；其余 `text/*`、JSON、XML、`+json`、`+xml`
  → `Text`；缺 `Content-Type` 或白名单外 → `WEB_UNSUPPORTED_CONTENT_TYPE`（消息含状态码）。
- charset 缺省 UTF-8；未知标签与 UTF-16/32 → `WEB_UNSUPPORTED_CONTENT_TYPE`；
  非法字节 → U+FFFD（REPLACE）；剥 BOM。
- **`Content-Encoding` 必须是 identity（缺失等同 identity）**；`gzip`（含大小写变体）→
  显式 `WEB_UNSUPPORTED_CONTENT_TYPE`，消息说明 identity-only 且未尝试解码。
- 总截止 30s（可配置 1..600）：连接取 `min(10s, 剩余)`，握手/读都设剩余时间；
  超时 → `WEB_TIMEOUT`。取消：`signal.onCancel` 关闭当前 socket 唤醒阻塞读，
  `IOException` 先 `checkAbort()` 再翻译——取消抛 `AbortedException`，绝不包装成 `WebException`。
- 声明超限 → `WEB_TOO_LARGE`；流式超限 → 截断 + `truncated=true`。

## 3. 评审重点 A：TOCTOU 闭合的可观测证据

实现层面“解析一次 + 钉地址 + TLS 用主机名”已在上文摘录。测试用回环 HTTPS 真跑
（运行时 `keytool` 生成自签 EC PKCS#12，SAN `dns:pinned.test,dns:second.test,ip:127.0.0.1`），
注入 `MapResolver` / `RecordingGate` / 自签信任的 `SSLContext`：

| 性质 | 用例与断言 |
|---|---|
| 每跳解析恰一次 | `pinsAddressAndSendsOnlyFixedHeaders`：`resolver.calls() == ["pinned.test"]`（`PinnedHttpClientTest:58`） |
| 解析结果整组过闸 | 同上：`gate.calls() == ["pinned.test=1"]`（:59） |
| TLS 用 URL 主机名而非地址 | 服务端观察 SNI `["pinned.test"]`（:60）；Host 头 `pinned.test:<port>` |
| 连接目标是钉住地址 | 夹具只监听 `127.0.0.1`，解析器返回回环，请求真实到达（全部真跑用例） |
| endpoint identification 用 URL 主机 | `hostnameVerificationFailureIsNetworkError`：`mismatch.test` 解析到回环、证书 SAN 不含该名 → 握手失败 `WEB_NETWORK_ERROR`，消息含 `mismatch.test`（:127） |
| 字面 IP 不回落解析、仍过同一闸 | `literalIpSkipsResolverButStillGoesThroughGate`：resolver “调用即 AssertionError” 且零调用；`gate.calls() == ["127.0.0.1=1"]`（:88）；无 SNI（:89） |
| 多答案任一非公网 → 连之前整组拒 | `anyNonPublicAnswerRejectsWholeSetBeforeConnecting`：答案 `[93.184.216.34, 10.0.0.1]`（公网在前）→ `WEB_BLOCKED_ADDRESS`，消息含 `10.0.0.1`，夹具零请求，resolver 恰一次（:112） |

“连之前”由公网答案排在私网答案之前仍报私网证明：闸扫描整组，不是“连第一个成功就放行”。

**证明边界（诚实声明）**：测试用注入 resolver 证明“调用次数与顺序”与“连接目标取自返回值”；
生产 `AddressResolver.system()` 就是 `InetAddress::getAllByName`，中间没有第二层解析缓存。
系统 resolver 自身的阻塞不可被取消（见 §10 残余 1）。

## 4. 评审重点 B：重定向逐跳校验

| 性质 | 用例与断言 |
|---|---|
| 跨源逐跳：URL 策略 + 重新解析 + 重新过闸 + 重新钉地址 | `followsCrossOriginRedirectWithPerHopResolutionAndNoForwardedHeaders`：resolver 恰 `["pinned.test", "second.test"]`（:165）；gate 两跳各一次（:166）；第二跳 Host/SNI = `second.test`；无转发头 |
| 相对 Location 以当前 URL 为基准 | `followsRelativeRedirectOnSameOrigin`：`/a` → `/b`，最终 URL 与正文正确 |
| 301/302/303/307/308 一律按 GET | `followsAllRedirectStatusesAsGet`（参数化 5 例）：第二请求行 `GET /next HTTP/1.1`（:142） |
| https→http 降级拒绝 | `rejectsRedirectDowngrade`：`WEB_REDIRECT_BLOCKED`，且只有首跳一次请求（明文请求从未发出） |
| 缺 Location 拒绝（不把 302 当普通结果） | `rejectsRedirectWithoutLocation`：`WEB_REDIRECT_BLOCKED`，消息含 `Location` |
| ≤ 5 跳 | `stopsAfterFiveRedirects`：第 6 个 3xx 处拒绝，消息含 `5`，请求数 6 |
| 坏/缺/空 Location、`javascript:`、坏方括号 | `FetchUrlPolicyTest#badOrMissingLocationIsBlocked`：全部 `WEB_REDIRECT_BLOCKED` |

**停点内修正的真实缺口**：实现原先只在 `isRedirect(status) && location != null` 时进入
重定向分支；302 缺 `Location` 会落到“正常结果”路径并尝试解析正文，与四确认 T3
“坏/缺 Location 拒绝”不符。已改为 `if (isRedirect(status))` 并在 `resolveRedirect`
内对缺失/空白 Location 抛 `WEB_REDIRECT_BLOCKED`；新增 `rejectsRedirectWithoutLocation`，
突变 M7 证明该用例能抓住回退。

## 5. T2 三条件逐条对账

| 条件 | 实现 | 证据 |
|---|---|---|
| ① chunked 传输解码必须实现并测试 | `FetchProtocol` 手写 chunked 解码（扩展/trailer/严格 CRLF/上限截断），端到端 TLS 真响应 | `FetchProtocolTest` 18 例（含 4 正向 + 9 负向帧）；`PinnedHttpClientTest#decodesChunkedEndToEnd`；突变 M4 |
| ② gzip 显式 `WEB_UNSUPPORTED_CONTENT_TYPE`，不解码不猜测 | `FetchContent.requireIdentityEncoding` 只放行缺失/identity；gzip 报错消息明示 identity-only | `FetchContentTest#nonIdentityContentEncodingIsExplicitlyUnsupported`；`PinnedHttpClientTest#gzipIsExplicitlyUnsupportedWithoutDecoding`；突变 M5 |
| ③ 字面 IP 与 DNS 结果同闸 | URL 策略 `ofLiteral` 校验后标记 `ipLiteral`；传输层对字面量仍调用 `gate.verify` | `literalIpSkipsResolverButStillGoesThroughGate`；`PublicAddressGateTest#literalIpGoesThroughTheSameGate`；突变 M1 |

## 6. 偏离清单

- **D1（停点状态惯例）**：`docs/plan/iteration-28.md` 停点表停 1 仍为 `☐`，按既有惯例
  在放行并提交后才转 `☑（已提交，评审记录 …）`；本 packet 不修改计划状态。
- **D2（契约口径）**：`WebFetchRequest` 只保证非 null；空串不在契约层抛
  `IllegalArgumentException`，统一交 Provider 的 URL 策略抛 `WEB_INVALID_URL`。
  理由：错误出口单一，避免调用方按异常类型分叉。
- **D3（夹具形态）**：计划写“回环 HTTPS（`HttpsServer` + 自签 PKCS#12）”；实现用原始
  `SSLServerSocket` 逐字节写响应。理由：需要观察 SNI、构造畸形 chunk/CRLF/超长行，
  `HttpsServer` 无法提供这些控制面。生产代码零影响。
- **D4（3xx 正文）**：重定向分支在读取正文前返回，3xx 的正文不进入内容白名单/大小判定；
  连接关闭即丢弃。理由：重定向语义只依赖 Location，读取不可信正文只增加攻击面。
- **D5（IPv6 口径细化）**：计划写“只接受 2000::/3，并显式拒 Teredo/6to4/文档/3fff”；
  实现把 `2001:0000::/23` 整段拒（含 Teredo 与同段基准/协议分配），而非只拒
  `2001::/32`。更严，且有逐段测试。
- **D6（1xx 处理）**：`FetchProtocol` 直接拒绝 1xx（含 100 Continue 与 101 Switching
  Protocols），不实现 `Expect: 100-continue` 交互。理由：请求只有 GET 且不带 `Expect`；
  客户端从未请求升级，101 不作为结果透传。评审补测后 101 单独用例钉住（M9）。
- **D7（测试反射）**：两个模块 `module-info` 均 `opens` 自身包给 JUnit 运行时反射；
  不影响导出面与生产可见性。
- **D8（突变引发的测试加固）**：M4 第一次只让“泛化 CRLF 断言”变红；已把断言改为
  专用消息 `chunk data is not CRLF-terminated`，并新增裸 LF 输入（跳过校验时会静默接受），
  再跑突变确认判别力。这是停点内唯一“突变暴露测试弱点”的例子，已登记。

## 7. 论证

1. **为什么自建而不是 `HttpClient`/`HttpsURLConnection`**：两者都不允许“解析一次、
   钉住地址、同时保留 SNI/endpoint identification 用主机名”的可验证组合；
   `HttpClient` 无连接级地址注入，`HttpsURLConnection` 的 DNS 与连接不可分离。
   自建把 TOCTOU 窗口压到“一次解析 → 同一地址对象 → connect”这一条可测试路径。
2. **为什么整组拒绝而不是“第一条公网即可”**：多答案里夹私网是常见 DNS rebinding/
   SSRF 绕过；只校验将连接的那条，会让“解析结果顺序”成为安全变量。整组拒绝把顺序
   从安全面移除。
3. **为什么字面 IP 也要过闸**：字面量绕过解析器，但仍是用户可控目标；
   `http://127.0.0.1` / `[::1]` 是 SSRF 的第一课。`ofLiteral` 只做语法校验，安全判定
   必须与 DNS 结果同一份代码。
4. **为什么重定向一律 GET**：本客户端没有请求体/方法面；307/308 的“保持方法”语义
   在只有 GET 时等价。统一为 GET 消除方法歧义。
5. **为什么跨源允许但不转发头**：跨源重定向是正常 Web 行为（短链、CDN）；
   由于请求面本来就没有凭据/ Cookie，跨源不产生凭据泄露；逐跳完整复验保证每一跳
   仍受同一安全策略。
6. **为什么 gzip 不自动解码**：T2 条件 ② 明确“不解码不猜测”。自动解压会引入
   zip bomb、长度上限失真、编码歧义三类风险；显式报错让调用方知道服务器未遵守
   `Accept-Encoding: identity`。
7. **为什么 302 缺 Location 必须拒绝**：把它当普通结果会让“重定向失败”伪装成
   “200/302 正文”，消费面可能把登录跳转页当资料；拒绝让协议异常 fail loud。
8. **为什么内容类型白名单而非黑名单**：HTML/文本是资料面；二进制/压缩/字体等
   不在需求内。白名单让新增类型必须显式过设计，而不是默认放行。

## 8. 验证摘要（全部实际执行）

```text
$ ~/Documents/apache-maven-3.10.0/bin/mvn -B -q validate
VALIDATE_EXIT=0        （仅 Spotless 内部 sun.misc.Unsafe 弃用告警）

$ ~/Documents/apache-maven-3.10.0/bin/mvn -B -pl web/web,web/fetch-http -am test
EXIT=0
WebContractTest          8
FetchProtocolTest       18
PublicAddressGateTest   47
PinnedHttpClientTest    22
FetchUrlPolicyTest      13
FetchContentTest         9
WebFetchHttpPluginTest   4
模块汇总：harness-web-web 8；harness-web-fetch-http 113；合计 121
```

- 钉地址/重定向用例（时间敏感：1s 超时、取消唤醒）连续重复 3 次：22/22 绿，
  1.677 s / 1.704 s / 1.672 s。
- 突变 M1–M8（基线备份 `/tmp/it28-mut/`，逐项破坏必红 → 还原复绿；还原后 `diff -q`
  静默、shasum 与基线一致；`grep -rn MUTATION web/ --include="*.java"` 零命中）：
  M1 字面 IP 绕过闸；M2 每跳解析两次；M3 接受 http；M4 跳过 chunk 数据 CRLF 校验；
  M5 放行 gzip；M6 整组校验只看第一条；M7 缺 Location 回退为普通结果；
  M8 契约状态码上界 599→600；M9（评审补测）放行 101。
- 计数口径：按模块汇总行，不把 `-am` 依赖模块的用例相加。

## 9. 证据索引

| 文件 | 覆盖 |
|---|---|
| `docs/plan/evidence/iteration-28/stop1-fetch-policy.txt` | URL 策略 13、地址闸 47、内容 9、上限/超时/取消、突变 M5/M6/M8、逐网段表 |
| `docs/plan/evidence/iteration-28/stop1-pinning-and-redirect.txt` | TOCTOU 六条性质、重定向矩阵、突变 M1/M2/M3/M7、残余 |
| `docs/plan/evidence/iteration-28/stop1-wire-chunked.txt` | 状态/头区矩阵、chunked 正负向、端到端、突变 M4 与测试加固 |
| `docs/plan/evidence/iteration-28/stop1-validate.txt` | validate EXIT=0、121 用例模块汇总、重复稳定性、shasum、文件面 |
| `docs/plan/evidence/iteration-28/stop1-review-packet.md` | 本 packet |

## 10. 请裁事项与残余

1. **DNS 解析不可被总截止/取消硬中断**：总截止约束连接/握手/读写；系统
   `getAllByName` 若自身阻塞，`AbortSignal` 无法唤醒。当前接受为残余（不引入
   异步解析线程/自建 resolver）。是否升级为“异步解析 + 超时放弃”？
2. **自定义 NAT64 前缀（RFC 7050 发现）**：只封 `64:ff9b::/96` 与 `64:ff9b:1::/48`；
   自定义前缀在地址形态上仍是 `2000::/3` 全球单播，地址闸无法仅凭地址识别其转换语义。
   已按 R2 登记 B17；是否在停 1 升级为前缀发现？
3. **3xx 正文不读**（D4）：是否接受“重定向响应体直接丢弃、不做内容/大小判定”？
4. **夹具用原始 `SSLServerSocket` 替代计划中的 `HttpsServer`**（D3）：是否接受？
5. **`WebFetchResult` 允许 1xx 状态码**（契约界 100..599），但传输层拒绝 1xx；
   契约是“结果事实容器”，是否接受这一宽松上界由 M8 钉死（599）？
6. **无代理/Cookie/Authenticator**：结构性不读取进程级默认值；当前无显式代理需求，
   故无配置面。是否接受为 0.3 边界？

以上 1/2 为安全相关残余，其余为实现/契约口径确认；未静默处理。

## 11. 停点声明

停 1 完成，等新鲜放行：**未 commit、未修改停点表、未进入停 2**。
放行后按计划提交本停点（含评审记录），停点表停 1 转已提交，再开停 2
（`web/search-deepseek` Provider + 真实探针闸）。
