# 0009 — LSP 诊断锚定 JH 观察时点，不证明 LS 分析来源

- 状态：已裁决（实施中）
- 日期：2026-10-29

## 背景

固定 JDT LS 的两次真实诊断均无 version；修改后的 seq400 是警告而非空数组。裸进程工程闭环成立，但无法据此证明 LS 分析来源版本或目标版本分析完成。把收到通知时的当前 hash 贴回通知不能补成来源证明。

## 裁决

- 停 0 通过并关闭（限定结论），JDT LS 路线确认，compiler API 备选不触发。
- T1 改为 JH 屏障拉取时点计算并落账文件 / 项目 hash；LS version 若在场只参考。不是对 LS 来源的保证。
- 四态基于 JH 事实：未就绪 / 过期 / 超时 / 拉取时点无诊断。最后一态须拉取成功、项目代稳定、经过明确等待窗；不称“项目无错误”，不声称 LS 完成分析。非空诊断同样只携观察锚。
- T1 解除实现阻塞，具体类型与执行判据在停 1 契约评审；禁止异步通知未经落账直改模型上下文，回放零联网零进程。
- T2 主动宿主探测、T3 重复启停 / 强制退出 / 全部 server 请求应答已登记 B23–B25；停 0 不替代产品验收。

## 推翻对象

- `docs/plan/iteration-29.md` 原 `Diagnostic Freshness Contract` 的“真零诊断必须证明目标版本分析完成”以及正常非空诊断须有 LS 来源版本关联的要求。
- 冻结 `stop0-review-packet.md` 中“T1 仍阻塞”是用户裁决前提案，不再作为现行开工阻塞；不改写该 packet 或原始诊断帧。
- 四确认中的“对应源码版本”现解释为 JH 观察锚，不解释为 LS 已证明来源版本。R1 可重建性与观察稳定性仍分别验收。

## 后果

可以实现有明确等待窗与稳定检查的观察型诊断，而非无限等待服务端不会提供的证明。无版本迟到通知仍可能混入本次观察，项目依赖 / 索引变化也不总可观测；这些是保留限制，不以 hash 隐去。seam 必须将未就绪、失稳和超时与成功空结果结构区分。Provider、Consumer 与装配未实现，T4–T7 未整体获批。

## 证据

- [用户终裁](../../docs/plan/evidence/iteration-29/stop0-acceptance.txt)
- [冻结审查提案](../../docs/plan/evidence/iteration-29/stop0-review-packet.md)
- [真实协议](../../docs/plan/evidence/iteration-29/stop0-run-01/protocol.txt)
- [尾流分析](../../docs/plan/evidence/iteration-29/stop0-run-01/protocol-tail-analysis.txt)
- [当前目标态](../../docs/design/05-capability-seam.md#81-lsp-observation-and-process-contractit29)
