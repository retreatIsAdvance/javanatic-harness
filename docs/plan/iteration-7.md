# 迭代 7 — openai-compat 重构 + 治理上线(verify/policy + 审批三模式 + fs 根目录)(已完成)

模块:llm/openai-compat(新)+ llm/deepseek(薄化)、fs/local、core/tools + interaction/approval、session/persistence(durable 自述)、examples/headless(新)(设计:05、07 §6、02)
提交:fb53db4(openai-compat)→ 1817fb6(fs 根目录)→ f51a9c7(审批三模式)→ 收尾提交
四确认日期:2026-09-07;验收日期:2026-09-07

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

- [x] openai-compat:717 行 ≤750,deepseek 主代码 109 行 ≤200;假服务端测试 11 个迁入并新增**合并分块(DeepSeek 实测)/分离形状(OpenAI)双用例** + profile 端点/附加头用例;DeepSeekE2ETest 带 key 通过(2.3s);DeepSeekPluginTest 经 seam 冒烟
- [x] fs 根目录:LocalFsTest 8 测试(相对解析到 root、/etc/hosts 越界拒绝、../ 逃逸拒绝 + 原有读写改删列);SpineMain/R1/工具 e2e 全部改经 root
- [x] 审批:ApprovalModesTest 4 测试(ask 回调放行计数/denied error result 含原因/deny 全拒+DENY_ALL 自述/stdin n 与 EOF 均拒绝);HUMAN_GATE 自述;interaction/approval 主代码 119 行 ≤250
- [x] verify/policy:CLI 实跑证据——STANDARD 输出治理摘要(审批=AUTO 持久化durable=true limits=…)exit 0;PRODUCTION 拒 AUTO 且逐项指出「违规: policy=PRODUCTION 但审批为 AUTO(换 approval-ask / approval-deny)」exit 1;无 key 不装配 provider;HeadlessVerifyTest 3 测试
- [x] headless 真实跑通:`java -m …HeadlessMain "用 bash 执行 date +%Y-%m 并原样告诉我"` → 真模型 tool_use → bash 真执行 → 二步终答,12 事件完整落账(user/message→step→llm/request→assistant→tool/call→tool/result→step→…→turn/end)exit 0;AGENTS 命令段已补完整运行命令(TODO 偿还)
- [x] 预算:openai-compat 717≤750 / interaction-approval 119≤250 / headless ~330≤450 / fs-local 增量 ~45≤80 全达标
- [x] 文档同步:05(it7 增补两条)、07(§6 实现落定:程序化口径/无 key verify/三模式齐备)、README(状态行+路线表 7✅)、AGENTS(运行命令 TODO 偿还 + 现状 + stdin 测试坑)

## 验收后修正(如有)

| 提交 | 缺陷 | 修正 |
|---|---|---|
| f51a9c7 | surefire 下 `System.in` 是挂起流而非 EOF:stdinPromptRejectsOnEof 直接 readLine() 把测试类永久挂死 | 测试注入 `System.setIn(空流/含 n 流)` 并恢复;AGENTS 已知坑回填 |
