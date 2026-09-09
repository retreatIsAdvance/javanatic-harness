# 迭代 9 — Per-Agent 作用域:ScopedToolRegistry + Setup Window + Preset 组合(已完成)

模块:kernel/core(registrationScope)、core/tools(ScopedLayers/ScopedRegistry + API 变更)、core/agent + core/agent-loop(setup window/发布事件)、core/preset(新)、fs/tool + shell/tool + examples(消费方适配)、examples/headless(home 发现)(设计:06)
提交:9e28600(registrationScope)→ 4f53698/09a0457(ScopedRegistry)→ 0e54169(setup window)→ dfee0e3(mountView)→ fcdcb2b(preset)→ 收尾
四确认日期:2026-09-08;验收日期:2026-09-09

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

- [x] ScopedLayers + ScopedRegistryTest 5 测试(root 可见性/scoped shadow 同层单条/兄弟隔离/同层重复 fail loud + 跨层合法/close 回收且 root 不受影响)
- [x] 同上(ScopedRegistryTest 覆盖);ExecContext 收敛 executeOne 7 参→4 参(ParameterNumber 门禁拦截后重构,不豁免)
- [x] 四消费方 + R2 架构断言签名同步(Scope.class 入参表);既有测试全绿(ToolExecutorTest 19→适配、AgentLoop/Approval/FsE2E/ShellE2E/R1)
- [x] SetupWindowTest 3:scoped 工具仅本 agent 可见(root/兄弟不可见);setup 失败回滚(schemas 空 + CREATED 未发 + registry 无 agent);dispose 发 DISPOSED + 轮次完整 + 注销
- [x] PresetServiceTest 4:mount 可见性(agent 可见/组合层与兄弟不可见/close 回收);未知插件列全清单 fail loud;patch 动作拒绝;list 排序 + resolve/NoSuchElement;主代码 261 行 ≤350
- [x] HeadlessProfileTest 3:存在文件直用/名字命中 home(临时 user.home 注入还原)/未知 fail loud;CLI 冒烟:--verify exit 0 + 真实任务 7 事件 exit 0(全走新 scoped 路径)
- [x] 全量见收尾记录;预算:preset 261≤350 ✓;kernel 1213→1204(压回≈1200 线,loadAllUnder+mountView+registrationScope 三 API 属计划内)
- [x] 文档同步:06 §9 实现落定(六条)、AGENTS 现状、README 路线表 9✅

## 验收后修正(如有)

| 提交 | 缺陷 | 修正 |
|---|---|---|
| 4f53698 | executor 增参后 executeOne 7 参,ParameterNumber 门禁拦截 | 不豁免——收敛 ExecContext record(7→4 参),门禁哲学(重构优于豁免)首次实战 |
| fcdcb2b | loadAllUnder 初版对批外 requires fail loud——preset 行引用 tools(基座在组合层)必炸 | 批内顺序校验保留;批外 requires 视为外层组合已满足(语义写入 Javadoc,06 §9 记录) |
| fcdcb2b | base bundle 增 presets 行后,headless/bundle-base 测试 classpath 缺 core/preset → 双向校验拒 | 补依赖;AppBootTest 发现清单 + presets;**python 补丁静默未命中复发一次**(pom 锚文本不匹配)——加 assert 后修复 |
