# 迭代 1 — kernel（已完成）

模块：kernel/brand、kernel/core（2026-08-19 验收；验收后修正见文末）

## 四确认

- **内容**：brand 的 `Id<T>` 品牌类型；core 的统一 Scope（.scope）、两模式 Events（.events）、Plugin 装载（.plugin）
- **目标**：把 Cordis 的 Fiber/ScopeKey/Context 三件套合成一个 Scope——生命周期 + 可见性 + 服务 overlay 一体；R3（副作用消除）获得结构性保证（原子回滚 + 无缓存解析）
- **为什么**：一切皆插件的地基——插件需要「失败即整体消失」的挂载语义，先有内核才有后续 seam / session / loop
- **不做**：kernel/config（无消费者不建）；响应式 reload；isolate 隔离（子 scope overlay 替代）；任何第三方运行时依赖；preview 特性

## 验收（证据 = 实际执行的命令与结果）

- [x] 全 reactor 构建绿：`mvn -B -q package` exit 0（38 项目，提交 7f5720f 时点）
- [x] kernel/core 仅依赖 java.base：module-info 无任何 `requires`
- [x] 主代码 ≤1200 行：实测 `find kernel/core/src/main -name "*.java" | xargs wc -l` = **1179**
- [x] 测试清单全绿（33 项）：ScopeTest 10（含 jqwik 属性「teardown 恒为注册逆序」）、EventsTest 11、PluginLoaderTest 12
- [x] 零第三方运行时依赖；无 `--enable-preview`；日志仅 System.Logger

## 验收后修正（讨论期发现，均已回归测试）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| f859dc6 | 插件 teardown 时自己的服务已被摘除 | ScopeImpl 无栈注册通道（registerService）+ PluginScope：服务摘除恒为插件回收最后一步 |
| 74782f5 | Subscription 命名以事件侧动词覆盖一切注册 | 更名 Disposable（撤销凭据） |
| ae912f5 | 挂载视图订阅绑插件私有房，收不到应用层派发（审批门/审计类插件失效） | ScopedEventsImpl bind/owner 分离 + Events.forMount（33→含 2 个新验收测试） |
