# 迭代 9 — Per-Agent 作用域:ScopedToolRegistry + Setup Window + Preset 组合(进行中)

模块:kernel/core(registrationScope)、core/tools(ScopedLayers/ScopedRegistry + API 变更)、core/agent + core/agent-loop(setup window/发布事件)、core/preset(新)、fs/tool + shell/tool + examples(消费方适配)、examples/headless(home 发现)(设计:06)
提交:①ScopedToolRegistry + 四消费方 → ②setup window + 发布事件 → ③preset 模块 → ④home 发现 + 文档验收
四确认日期:2026-09-08

## 四确认(已确认 2026-09-08)

- **内容**:
  1. **ScopedLayers + ScopedToolRegistry**(06 §3/§4):`ToolRegistry` 改为 `register(Scope owner, tool)` / `schemas(Scope)` / `resolve(Scope, name)`;scoped 盖组合层(shadowing,merge 最近优先),同层重复 fail loud;层随 owner scope 关闭回收(R3);kernel 增 `Scope.registrationScope()`(挂载视图的共享层归属——root 挂载落 root、agent 挂载落 agentScope,统一约定,~8 行);`ToolExecutor.execute` 增 scope 参数;消费方四处同步(fs-tool/shell-tool/loop/executor)与 R2 架构断言
  2. **Setup Window**(06 §5):`CreateAgentOptions` 增 setup 回调——agentScope 建立后、发布前执行,失败 close 回滚半注册、不发布;`AgentEvents.CREATED/DISPOSED` 发布事件;dispose 链补 persistence flush barrier
  3. **Preset 组合**(06 §6):新模块 `core/preset`(SnakeYAML,第四 YAML 边界)——`AgentPresets` 服务(list/resolve/mount);preset.yml 行复用 `ConfigRowSpec` 语义(小规模解析重复,第二个消费者出现时上提共享);mount 在 setup window 内经 PluginScope(shared=agentScope) 装载,per-plugin 原子回滚保留
  4. **Home profile 发现**:`--profile=<name>` 解析 `~/.harness/profiles/<name>/profile.yml`;显式路径仍可用
- **目标**:一个 agent 拥有自己的工具集并与其他 agent 隔离(组合层观察全部、agent 看不到兄弟);per-session 能力集从数据挂载
- **为什么**:06 是 subagent/persona/restriction 的地基;**API 破坏性变更清零是 0.1.0 首发前置**(it10 发布工程后四 seam 冻结);R3 首次获得 setup 事务形态的真实消费者
- **不做**:Restriction(§8——与 sandbox 配对进 it11+);composeFrom 继承(消费者是 subagent delegation);GAV 版本钉扎(发布切片);sandbox/commands/bundle-headless 占位不动;SystemPromptService scoped 化(persona 需求出现再进——避免一次改两个 seam)

## 验收(证据 = 实际执行的命令与结果)

- [ ] ScopedLayers:同层重复 fail loud / 跨层 shadowing 最近优先 / 层随 scope 关闭回收
- [ ] ScopedToolRegistry:root 挂载工具对 agent 可见;agent 挂载工具对兄弟不可见;同名 scoped 盖 root;dispose 后注册表空
- [ ] 四消费方:fs-tool/shell-tool 经 register(scope,tool);loop 按 agentScope 取 schema;executor 按 scope 解析——既有全量测试(R2 架构断言签名同步)绿
- [ ] Setup window:setup 注册的 scoped world 随 agent 生效;setup 抛异常 → agentScope 回滚 + 不发布 CREATED;dispose 发 DISPOSED 且 flush barrier 执行
- [ ] Preset:readonly preset(preset.yml)mount 后 agent 只见该工具集;失败回滚;list/resolve;mount 后 dispose 注册表回收
- [ ] Home 发现:`--profile=headless` 命中 `~/.harness/profiles/headless/profile.yml`;名字不存在 fail loud;显式路径不受影响
- [ ] 全 reactor package 绿(225 → 245+);预算:tools 增量 ≤250 / agent+loop ≤250 / preset ≤350 / kernel 增量 ≤30
- [ ] 文档同步:06 实现落定(ScopedLayers 归属 core/tools、registrationScope 约定、setup 事务)、02/README/AGENTS 现状、iteration-9 验收

## 验收后修正(如有)

| 提交 | 缺陷 | 修正 |
|---|---|---|
