# 迭代 7.1 — headless 运行时灵活化(provider/model/key/base-url)(已完成)

模块:llm/openai-compat(+通用插件)、examples/headless(CLI 参数化)
提交:feat(llm) 通用插件 → feat(headless) 参数化 + 测试 → docs 收尾
四确认日期:2026-09-07;验收日期:2026-09-07

## 四确认(已确认 2026-09-07)

- **内容**:
  1. CLI 参数:`--provider`(默认 deepseek)、`--model`(默认 deepseek-chat)、`--base-url`(默认 DeepSeek 官方)、`--api-key-env`(默认 DEEPSEEK_API_KEY)、`--api-key`(字面量,优先于 env,警示 shell history);默认值集中 headless 一处
  2. headless 组合改走 `OpenAiCompatPlugin`(llm/openai-compat 新增:adapter 名 + profile + transport 构造)——任意 OpenAI 兼容厂商一条命令直跑;DeepSeekPlugin 保留为厂商薄壳示例
  3. 测试:参数解析单测(默认/优先级/非法 fail loud);假服务端 e2e(headless `--base-url` 指向本地假服务端,假 key 跑通完整 turn);真实 CLI 冒烟(deepseek 带 key)
  4. AGENTS 运行命令段与 README 同步
- **目标**:一条命令适配任意兼容厂商/模型,key 来源可配,无硬编码厂商绑定
- **为什么**:组合位的职责是显式接收部署差异;it7 重构后这只需一个通用插件;it8 ConfigService 落地后同组参数换轨 YAML,CLI 形状不变
- **不做**:YAML/配置文件(it8);多 provider 挂载与路由切换;非兼容协议;凭据存储文件(CredentialsService 挂账);交互式输入 key

## 验收(证据 = 实际执行的命令与结果)

- [x] OpenAiCompatPlugin(llm/openai-compat,+48 行):构造 (名称, profile, transport),挂自身 scope 注册/注销
- [x] HeadlessOptionsTest 8 测试:默认值四项/自定义厂商 flags/字面量优先/未设 env→null/未知 flag 与第二任务 fail loud/--verify 无 key 过/PRODUCTION 拒 AUTO/无 key 任务 exit 2
- [x] HeadlessFakeServerE2ETest:--provider=vendor-x + 假服务端 + 假 key → 完整 turn exit 0
- [x] 真实 CLI 冒烟:默认 deepseek 走通用插件,「只回复两个字:正常」→ 7 事件 exit 0
- [x] --verify 无 key 不装配 provider(HeadlessOptionsTest 两档断言)
- [x] 文档同步:AGENTS 命令段多厂商示例;headless 主代码 ~330 ≤500;openai-compat 增 48 行

## 验收后修正(如有)

| 提交 | 缺陷 | 修正 |
|---|---|---|
