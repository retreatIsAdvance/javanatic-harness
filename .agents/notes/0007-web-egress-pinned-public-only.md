# 0007 — Web 出口：钉地址公网抓取，egress 不受沙箱治理

- 状态：已实施（it28 停 1）
- 日期：2026-10-21

## 背景

网络资料获取是本项目第一个**由模型输入驱动出口**的能力：URL 来自工具参数，可能指向内网、回环、云元数据或经 DNS rebinding 指向私网。沙箱治理的是本机进程（文件/进程/exec），**不拦网络出口**；因此 Web 的安全边界必须在传输实现内部自持，而不是指望沙箱。dsh 的 `packages/web/web-fetch-http/src/network.ts` 是结构先例：解析一次、整组校验、把连接钉在已校验地址上。

## 裁决

- **传输自持**：`web/fetch-http` 手写 `SSLSocket`（SNI + endpoint identification、ALPN http/1.1），零第三方。不采 JDK `HttpClient`（无逐请求地址钩子，DNS rebinding TOCTOU）与 `HttpsURLConnection`（继承进程级 Cookie/Authenticator/代理默认值）。
- **公网闸（T2 ③）**：一次解析、整组校验、钉住其一连接；URL 字面 IP 与 DNS 解析结果走同一闸；IPv4/IPv6/NAT64（`64:ff9b::/96`、`64:ff9b:1::/48`）/6to4/Teredo/mapped 全覆盖。自定义 NAT64 前缀不做 RFC 7050 发现（残余登记 B17）。
- **重定向（T3）**：301/302/303/307/308 一律 GET 重发；跨源允许但**逐跳**重跑 URL 策略 + 地址闸 + 重新解析 + 重新钉地址；绝不转发任何头/凭据；拒 https→http 降级、坏/缺 `Location`、超 5 跳。
- **内容策略**：文本白名单 + 只接受 `Content-Encoding: identity`；服务器无视 identity 回 gzip/br/deflate → 显式 `WEB_UNSUPPORTED_CONTENT_TYPE`，不解码不猜测（T2 ②）。
- **egress 不受沙箱治理**（显式接受）：网络权限由组合给——`web-fetch-http` 行挂载即授权公网 HTTPS；安全边界 = 公网闸 + 钉地址 + 逐跳复验 + 无凭据转发 + 审批与审计。
- **契约增补（停 1 内成形）**：`WebFetchResult` 增 `contentType`——消费面要外显 `Content-Type`，且停 1 测试内已钉住；0.3.0 未发布面无兼容包袱。

## 推翻对象

- 「网络能力可复用 JDK `HttpClient` 的默认解析/连接」的默认——没有逐请求地址钩子，地址校验与连接之间存在 TOCTOU 窗口。
- 「重定向只在同源内跟随」（dsh 口径）——本迭代放宽为跨源逐跳全复验（T3 用户裁定），换取常见 URL 迁移可用；代价是每跳完整策略重跑。
- 「压缩由实现顺手解码」——`identity` 之外的编码一律显式拒绝（T2 ②），不引入解压依赖、不猜测。

## 后果

- 每跳新建连接、不复用；解析期 `getAllByName` 不受 deadline/取消约束（残余 B17，升级路径独立解析线程 + 限时 join）。
- 传输对消费面只暴露 `WebFetchService`；地址闸/resolver/`SSLContext` 只经构造器注入，**不得**演化为静态 setter / SPI / 系统属性开关（B22）。
- 抓取 URL 与搜索端点信任面不同：前者来自模型输入、逐次过闸；后者是组合者显式配置（B18）。
- 请求头固定、无 Cookie/代理/凭据配置面——越权面随配置面一起消失。

## 证据

- [stop1-fetch-policy.txt](../../docs/plan/evidence/iteration-28/stop1-fetch-policy.txt)：URL/地址闸/内容策略矩阵与残余范围。
- [stop1-pinning-and-redirect.txt](../../docs/plan/evidence/iteration-28/stop1-pinning-and-redirect.txt)：钉地址、逐跳重定向、TLS 夹具与残余 §4。
- [stop1-wire-chunked.txt](../../docs/plan/evidence/iteration-28/stop1-wire-chunked.txt)：HTTP/1.1 与 chunked 真帧。
- 设计同步：[02 §2 Web](../../docs/design/02-module-layout.md)、[05 §7.2](../../docs/design/05-capability-seam.md)、[07 §5](../../docs/design/07-profile-bundle.md)。
- 边界登记：[B17 / B18 / B22](../../docs/plan/boundary-register.md)。

## 关联

- 后继：[0008](0008-web-search-provider-and-token-gap.md)（搜索 Provider 与 token 缺口）。
