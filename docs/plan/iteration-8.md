# 迭代 8 — 组合数据化:AppBoot + ConfigService + YAML 三层 + CompositionManifest(进行中)

模块:kernel/config、bundle/base(新)、8 个插件配置化、core/session(header 清单)、examples/headless(迁移)(设计:07、03 §8、02)
提交:①kernel/config → ②插件配置化+ServiceLoader 双注册 → ③bundle/base(AppBoot+YAML+manifest) → ④headless 迁移 → ⑤文档验收
四确认日期:2026-09-07

## 四确认(已确认 2026-09-07)

- **内容**:
  1. kernel/config(零第三方):ConfigService(行未写 config→空 Map)、ConfigRow/PatchRow、ExpressionResolver(env:/props:/cwd/home + :- + ==/!= 白名单,fail loud;合并后加载前求值)
  2. bundle/base(SnakeYAML 第三第三方,仅此模块):AppBoot——bundle 资源(META-INF/harness/bundle.yml)按 profile 序叠加 → profile patch → CLI patch;replace/remove/insert(after/before)/disabled,目标缺失与同 id 双行 fail loud;双向显式校验;--dump-config;--verify/policy 上移;CompositionManifest 服务
  3. 插件配置化:8 个构造参数插件补无参 + apply 读 configFor(id())(record 校验 fail loud);显式构造器保留为程序化组合等价物;leaf 插件 ServiceLoader 双注册(module-info provides + META-INF/services)
  4. CompositionManifest 进 SessionHeader(session-store 消费;空清单=组合未知;版本钉扎挂账)
  5. examples/headless 迁移 AppBoot:内置默认 headless profile(YAML);it7.1 flags 桥接 config overlay;spine 保持直装对照
- **目标**:组合是数据(行序可 dump 可 patch),换 provider/配置不改代码;R1 三规则齐备;治理断言进 boot
- **为什么**:bundle/ConfigService 是剩余最大占位;headless 直装已是组合事实但形状是代码;manifest 是 it6 挂账;06 消费者是 subagent(it9)
- **不做**:06 全部(it9);~/.harness home profile 命名发现(显式路径+内置默认);bundle GAV 版本钉扎与插件版本摘要;ConfigService 类型化 getter;配置热更新;bundle/headless 模块;deepseek 薄壳进 ServiceLoader

## 关键设计决策

1. 安全边界无默认:fs root 与 persistence root 在 config 路径缺失即 fail loud;LoopGuard 等风险可控项允许插件侧文档化默认
2. 双向显式边界:ServiceLoader 发现 provides 声明的插件;程序化壳不注册不受约束
3. key 经 config:llm-openai-compat 行支持 apiKeyEnv(默认 DEEPSEEK_API_KEY)与 apiKey(脱敏)

## 验收(证据 = 实际执行的命令与结果)

- [x] 行模型为 sealed 动作联合(Include/Replace/Remove/Insert+Anchor,互斥动作不落布尔字段);**参数上限门禁**:checkstyle ParameterNumber max=6 + 注释豁免(R4 注入构造器/选项数据束,理由内联),红证在案(撤豁免 → AgentLoopImpl 10 参被拦,恢复绿)
- [ ] ExpressionResolver:插值/默认值/比较全形状 + 白名单外 fail loud(jqwik 或穷举用例)
- [ ] 行合并:bundle 叠加/patch replace/remove/insert(after/before)/disabled;目标缺失、同 id 双行 fail loud
- [ ] 双向显式:行引用未知 id 拒;发现未引用拒;程序化壳不受约束
- [ ] 插件配置化:8 插件无参构造经 config 装配与显式构造器行为一致;fs/persistence root 缺失 fail loud;ConfigService 行未写 config→空 Map
- [ ] ServiceLoader 双注册:discover() 发现全部 leaf 插件(模块路径与 classpath 两栖)
- [ ] AppBoot:--dump-config 输出最终行序;--verify 两档 exit 语义与 it7 一致;CompositionManifest 进 SessionHeader 且 JSONL 往返
- [ ] headless:经 AppBoot + 内置 YAML profile 跑通假服务端 e2e 与真实 CLI 冒烟;it7.1 flags 兼容
- [ ] 文档同步:07(实现落定)、02、README、AGENTS;预算 kernel/config ≤350 / bundle/base ≤900 / 插件增量 ~250 / headless ≤400

## 验收后修正(如有)

| 提交 | 缺陷 | 修正 |
|---|---|---|
