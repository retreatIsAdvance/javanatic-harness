# 迭代 7 — openai-compat 重构 + 治理上线(verify/policy + 审批三模式 + fs 根目录)(进行中)

模块:llm/openai-compat(新)+ llm/deepseek(薄化)、fs/local、core/tools + interaction/approval、session/persistence(durable 自述)、examples/headless(新)(设计:05、07 §6、02)
提交:①openai-compat 重构 → ②fs 根目录 → ③审批三模式 → ④verify/policy + headless → ⑤文档收尾
四确认日期:2026-09-07

## 四确认(已确认 2026-09-07)

- **内容**:
  1. **开头重构:openai-compat 通用适配器**——传输韧性与 OpenAI 兼容 wire 下沉至新模块 `llm/openai-compat`(VendorProfile:baseUrl/端点路径/附加头;TransportOptions:key/超时/重试);deepseek 薄壳化(DeepSeekOptions 公开形状不变,内部组装 profile+transport);假服务端测试搬入通用层并补「DeepSeek 合并分块 vs OpenAI 分离形状」双用例
  2. **fs-local 根目录策略**(生产阻塞挂账清偿):`FsLocalPlugin(root)` 构造注入;五操作全部经 root 解析(绝对路径须在 root 内、相对路径按 root 解析),越界 fail loud
  3. **审批三模式真实现**:`ApprovalService` 增加 `Mode mode()` 自述(AUTO/HUMAN_GATE/DENY_ALL);interaction/approval 提供 `approval-ask`(headless stdin y/n 默认,回调可注入供 UI 接管;非交互环境拒绝语义——缺省即拒绝)与 `approval-deny`(全拒);auto 留在 core/tools(executor 强制依赖的锚)
  4. **--verify + policy 档位(R4)+ examples/headless 最小 runner**:`SessionPersistence.durable()` 自述;治理摘要(approval mode/persistence durable/LoopGuard limits)+ `Policy.STANDARD/PRODUCTION` 装配期断言(production:mode≠AUTO、durable、limits 非零;违规 fail loud 退出非零);headless CLI(直装组合,`"task"` 真实跑通走 deepseek,`--verify` 无 key 可跑)——偿还 AGENTS「产品运行入口」TODO
- **目标**:R4 从构造器强制升级为可机器验证的治理证明;两个生产阻塞项清偿;多厂商接入成本从 ~600 行降到几十行 profile
- **为什么**:it6 后模型已能对真实机器产生真实副作用,治理是放行生产的前提;fs 越界比 shell 更早阻塞;openai-compat 趁单厂商时重构成本最低;headless 让运行入口承诺落地
- **不做**:YAML/ConfigService/bundle 三层叠加与 patch(完整 07 → it8);06 的 agent 兄弟作用域/事件冒泡/preset(subagent 语境 → it8);sandbox/进程组真隔离(挂账);Anthropic/Gemini 独立适配器(无消费者不预写);fs 之外 capability 的根目录化;交互 UI(仅 stdin 通道);policy 的更多档位(仅 STANDARD/PRODUCTION)

## 验收(证据 = 实际执行的命令与结果)

- [ ] 开头重构提交后:全 reactor package 绿;deepseek 假服务端测试全部迁至 openai-compat 并新增合并分块/分离形状双用例;DeepSeekE2ETest 留在 deepseek 且带 key 通过;deepseek 主代码 ≤200 行
- [ ] fs 根目录:root 内绝对/相对路径可用;越界(../ 逃逸、root 外绝对)fail loud;agent-spine 与既有测试全部改经 root
- [ ] 审批:ask 经回调注入 y→放行/n→denied error result;stdin 非交互(EOF)→ 拒绝;deny 全拒;mode() 三实现自述正确
- [ ] verify/policy:STANDARD+auto 过;PRODUCTION+auto 拒(退出非零且指出违规项);PRODUCTION+durable+ask+limits 过;无 key 时 --verify 可跑(不装配 provider)
- [ ] headless:带 key 真实跑通一个 task(命令与输出摘要落案);`--verify` 输出治理摘要;AGENTS 命令段落补「跑一个 task」
- [ ] 预算:openai-compat ≤750 / interaction-approval ≤250 / headless ≤450 / fs-local 增量 ≤80
- [ ] 文档同步:05(openai-compat 分层 + fs 根目录)、07(§6 实现落定:程序化组合口径)、02/README/AGENTS(现状 + 运行命令)、iteration-7 验收

## 验收后修正(如有)

| 提交 | 缺陷 | 修正 |
|---|---|---|
