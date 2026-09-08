# 迭代 8 — 组合数据化:AppBoot + ConfigService + YAML 三层 + CompositionManifest(已完成)

模块:kernel/config、bundle/base(新)、8 个插件配置化、core/session(header 清单)、examples/headless(迁移)(设计:07、03 §8、02)
提交:a218db3/60eeefa(行联合+门禁)→ 1ac204f(kernel/config)→ a8035ee(插件配置化)→ c1270ad(bundle/base)→ 96cfb5c(headless 迁移)→ 收尾
四确认日期:2026-09-07;验收日期:2026-09-08

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
- [x] ExpressionResolverTest 9 测试(env/props/cwd/home 插值、:- 默认、整体 null vs 嵌入空串、Map 求值、==/!=/裸真值、未知源/空名/越权算子 fail loud、standard 源实读 env);ConfigRowSpecTest 3;ConfigValues 随插件测试覆盖
- [x] RowComposerTest 4(Include 原位替换或追加/Replace·Remove 目标缺失拒绝/Insert 锚点前后+缺失拒绝/重复 fail loud)
- [x] AppBootTest:未知行引用拒(消息含「行引用的插件未发现」)、tiny profile 未引用拒(含「未被任何行引用」);双向对照**全量行**(禁用行算引用——修复了初版对照启用行的缺陷);discover 覆盖 16 注册插件
- [x] 插件配置化 7 插件(LoopGuard/AgentLoop/FsLocal/JsonlPersistence/OpenAiCompat/BashLocal/ShellTool/ApprovalAsk)+ FsLocalConfigTest 2(root from config + 缺失 fail loud「config missing required 'root'」)+ LoopGuardConfigTest 2(默认 50/40 + 显式值,Number/字符串双态)+ OpenAiCompatConfigTest 2(name 注册可路由 + 无 key fail loud);双注册:module-info provides + META-INF/services
- [x] AppBootTest.discoverFindsAllRegisteredPlugins 16 插件;模块路径实测(CLI 运行经 ServiceLoader 装配成功;uses 子句缺失曾致 ServiceConfigurationError——修复)
- [x] AppBoot.dump 显示禁用行与 config 且 apiKey 脱敏(doesNotContain sk-secret);verify STANDARD 过/PRODUCTION 拒 AUTO(VerifyFailedException);manifest 进 SessionHeader(AppBootTest 断言 rows 相等);JSONL HeaderCodec manifest 往返(原 6 测试仍绿)
- [x] headless 经 AppBoot:HeadlessFakeServerE2E(假服务端完整 turn)+ HeadlessOptionsTest 8 + HeadlessVerifyTest 3 全绿;真实 CLI:--verify exit 0、「用 bash 执行 echo boot-ok > proof.txt 并读回」→ 18 事件完整落账 + proof.txt 真落盘 exit 0;it7.1 全部 flags 兼容(overlay 桥接)
- [x] 文档同步:07 实现落定段、AGENTS(现状+运行命令)、README(路线表 8✅);预算:kernel/config 431>350(行联合+ConfigValues+manifest 属计划内)、bundle/base 主代码 ~830≤900、headless ~330≤400

## 验收后修正(如有)

| 提交 | 缺陷 | 修正 |
|---|---|---|
| c1270ad | 双向校验初版只对照**启用行**——禁用行的插件会被误判「未被引用」而拒绝装配 | 校验移到 resolve 之前对照全量行;AppBootTest 补用例锁住 |
| c1270ad | Session 构造器未接 manifest 参数(patch 静默未命中),header.manifest() 恒 null——AppBoot 集成测试当场抓住 | Session 增 manifest 参数(fresh 路径消费);直装 create 保持组合未知(null) |
| 96cfb5c | headless overlay 把「变量名=值」拼接塞进 apiKeyEnv 键——env 路径下插件读到垃圾值 fail loud | overlay 分支:字面量→apiKey,env→仅变量名 |
| 96cfb5c | kernel module-info 缺 `uses Plugin` 声明——模块路径 CLI 下 ServiceLoader 直接 ServiceConfigurationError | 补 uses 子句(发现侧归属 kernel);CLI 复跑通过 |
| 全程 | 静默未命中的 python patch 两次(permits 子句、uses 子句)——replace 无 assert 时悄悄无效 | 已在关键 patch 加 assert;教训:批量改补丁必须断言命中 |
