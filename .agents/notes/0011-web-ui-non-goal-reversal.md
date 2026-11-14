# 0011 Web UI 非目标改判：本地回环 Web UI 入 0.5 W4

日期：2026-11-13。裁决人：用户（it35 W3 四确认 T2）；追认：AI。

## 背景

it30 路线评审把 0.5 路线定为 W1 投影 → W2 TUI → W3 JSON-RPC server → W4 本地 Web UI，但 [00 §6](../../docs/design/00-overview.md) 长期挂着「**不做 Web UI**。headless runner + JSON-RPC（ACP 等价物）足够」——W4 已入路线、00 §6 却维持不做，两处口径互相矛盾；it30 已预登记改判推迟到 W3 开工前裁。

## 裁决（推翻对象）

**推翻**：00 §6 L198「不做 Web UI」整行（无差别的不做）。
**替代**：本地回环 Web UI 入 0.5 W4（约束预登记于 [iteration-30.md](../../docs/plan/iteration-30.md) L7④：无构建链单文件 vanilla JS、server 直接服务 .html、不引入 node 工具链）；**远程 / 多租户 Web UI 维持不做**（无远程暴露 = B29 硬线的 UI 面对应）。

## 为什么

W4 是 0.5「个人工作台」终点站的既定形状，与「headless runner + JSON-RPC（ACP 等价物）足够」的原始立场并不冲突——那句话反对的是把 JH 做成**服务/平台**（多租户、远程），不反对单用户本机消费自己的读侧数据。改判范围严格限定在本地回环：B29「仅 127.0.0.1、单用户、无远程暴露、无 TLS」的硬线对 UI 同样成立；远程/多租户面一字不动。

## T6 措辞解释（随档承载）

T6 裁决 v1 只读（清单 / 五视图 / tail），写侧控制面全不做。00 §6「ACP 等价物」措辞 = **形态类比依据，非控制面授权**——它说明「server 形态足够支撑本机消费面」这个判断从哪来，不授权 W3 把 ACP 的控制面（任务提交 / 审批 / 答复 / 取消）复刻过来。同节 L199「不做 ACP/Codex/Claude Code subagent 桥接」与该类比**并存不矛盾**：类比的是形态，不做的是桥接协议本身；W1/W2 两站只读立场在 W3 延续。

## 后果

- 00 §6「不做 Web UI」行改写（本地回环 Web UI 入 0.5 W4 + 远程/多租户维持不做）；
- [docs/design/README.md](../../docs/design/README.md) Out of Scope「Web UI」→「远程/多租户 Web UI」；
- boundary-register B29 行「另行决策记录」引向本记录；
- 全局 grep「不做 Web UI」旧措辞零残留（release.md 0.5 行已同步无需动，it35 四确认核对）。

## 证据

- [iteration-35.md](../../docs/plan/iteration-35.md) 裁决记录与 T2 行（2026-11-13 W3 四确认评审通过）；
- [iteration-30.md](../../docs/plan/iteration-30.md) L5/L7（改判推迟登记 + W4 首版约束）；
- [stop2-serve-design.md](../../docs/plan/evidence/iteration-35/stop2-serve-design.md) §4 同步面清单。
