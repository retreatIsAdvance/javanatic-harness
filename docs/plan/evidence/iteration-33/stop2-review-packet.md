# it33 停 2 review packet —— CLI 只读检视接线（--session / --session-view）

**新增模块自问（it30 裁决）**：本停不新增模块（否）——`workbench/projection` 停 1 已建；本停只做 headless 消费接线 + `dist/jh` moditect 登记（停 1 已预告延至此停，pits #38④ 勾销）。

**TL;DR（五行）**

- **不变量增量**：① `--session` 只读检视旁路——keyless、零写路径、不进 API key 检查、不建 agent（run() 顺序在 key 检查之前）；② 读/写 flag 一眼分清：`--session`（只读检视）vs `--resume`（续跑写侧），`--help` 与互斥矩阵同钉；③ 检视失败面与 --resume 同词表：id 不存在 exit 3 + 指引、写者锁冲突 exit 3（不等待、不写）；④ 五视图词表 `turns|tools|usage|timeline|messages` 与 projection 一一对应（缺省 turns 在 parse 应用）。
- **裁决点**：sessionView 缺省在 parse 应用（仿 provider/model 先例，run 只收已校验值）；exit 5 语义与检视无关（检视成功恒 0）；messages 视图透传 deriveMessages（停 1 已裁）。
- **风险自报**：LOG 走真实 System.err（e2e 只断言 exit 码/stdout，消息文本经提取的 static helper 钉死——若错只错词面、不错契约）；`--session` 与任务文本互斥按「一次只做一件事」从严（检视 + 任务同用无合法解读）。
- **证据入口**：[stop2-evidence.md](stop2-evidence.md)（聚焦 36/36 + 突变 M1–M3 必红→逐字节还原 BYTE-IDENTICAL + validate 0 违例 + verify 计数 8/8 + 真实会话五视图实录（委派树乱序 callId 配对 / aborted 多轮 / error 工具））。
- **若只能改一处**：改 `run()` 的检视旁路位置——它钉死「不进 API key 检查」：旁路若下移到 key 检查之后，keyless 检视整个失效且聚焦测试必红（`sessionViewReadsOnDiskSessionKeyless` 无 key 跑 exit 0）。

## 承载面（HeadlessMain 接线相关段 + SessionViewRenderer）

### parse 分支与校验（HeadlessMain）

```java
    /** `--session-view` 词表：与 workbench/projection 五视图一一对应（缺省 turns）。 */
    private static final List<String> SESSION_VIEWS =
        List.of("turns", "tools", "usage", "timeline", "messages");

    // parse 分支：
            } else if (arg.startsWith("--session=")) {
                session = valueOf(arg);
            } else if (arg.startsWith("--session-view=")) {
                sessionView = valueOf(arg);
                if (!SESSION_VIEWS.contains(sessionView)) {
                    throw new IllegalArgumentException("--session-view 只支持 "
                        + String.join("|", SESSION_VIEWS) + ",收到: " + sessionView);
                }
            }

    // 校验块（互斥矩阵）：
        if (sessionView != null && session == null) {
            throw new IllegalArgumentException("--session-view 需与 --session 同用（单独指定视图无从检视）");
        }
        if (session != null) {
            // 检视是只读旁路:与"要跑什么"的参数同用即为用法错误(不猜意图)
            if (sessionView == null) {
                sessionView = "turns"; // 缺省视图在 parse 应用(仿 provider/model 先例)
            }
            if (task != null) {
                throw new IllegalArgumentException("--session 与任务文本互斥（检视不执行任务）");
            }
            if (resume != null) {
                throw new IllegalArgumentException("--session 与 --resume 互斥（检视与续跑请分开运行）");
            }
            if (verify) {
                throw new IllegalArgumentException("--session 与 --verify 互斥（一次只做一件事）");
            }
            if (sessions.isPresent()) {
                throw new IllegalArgumentException(
                    "--session 与 --sessions 互斥（指定单会话与列举请分开运行）");
            }
        }
```

### 只读旁路与失败词面（HeadlessMain）

```java
    // run()：sessions 列举块之后、API key 检查之前——
        if (options.session() != null) {
            // 只读检视旁路:不进 API key 检查、不建 agent、零写路径——呈现落盘投影
            return runSessionView(rt.root(), options.session(), options.sessionView(), out);
        }

    /** --session 只读检视:load → 投影 → 纯文本渲染(零写路径;占用即拒不等待)。 */
    static int runSessionView(Scope root, String sessionId, String view, PrintStream out) throws Exception {
        try {
            SessionPersistence.Loaded loaded =
                root.require(SessionPersistence.KEY).load(Session.newId(sessionId));
            SessionViewRenderer.render(loaded, view).forEach(out::println);
            return 0;
        } catch (NoSuchElementException e) {
            LOG.log(Level.ERROR, "{0}", unknownSessionText(sessionId, "--session=<id>"));
            return 3;
        } catch (IOException | CompletionException e) {
            WriterLockException lock = writerLockCause(e);
            if (lock == null) {
                throw e;
            }
            LOG.log(Level.ERROR, "{0}", busySessionText(sessionId, lock));
            return 3;
        }
    }

    static String unknownSessionText(String sessionId, String retryFlag) {
        return "会话不存在: " + sessionId + "；用 jh --sessions 查看可用会话，再 " + retryFlag;
    }

    /** --session 检视遇写者占用:与 --resume 同 fail-loud 词表(写者锁冲突 → exit 3)。 */
    static String busySessionText(String sessionId, WriterLockException lock) {
        return "会话被另一写者占用（写者锁冲突）: " + lock.getMessage()
            + "；先结束占用该会话的进程/实例，再重试 --session=" + sessionId;
    }
```

### SessionViewRenderer（headless 渲染面，package-private）

```java
final class SessionViewRenderer {
    static List<String> render(SessionPersistence.Loaded loaded, String view) {
        SessionViews views = SessionProjection.project(loaded);
        List<String> out = new ArrayList<>();
        out.add("session=" + views.header().id() + "  view=" + view
            + "  events=" + views.header().eventCount());
        switch (view) {
            case "turns", "tools", "usage", "timeline", "messages" -> …  // 五视图逐行
            default -> throw new IllegalArgumentException("未知视图: " + view);
        }
        return out;
    }
    // 辅助：tokens(TokenUsage) / usageText(TokenUsage) / params(TreeMap 键排序) /
    // sourceName(MessageSource 穷尽 + default 简单类名) / blocksText(ContentBlock 穷尽) /
    // detail(TimelineDetail 穷尽 12 变体) / escape(\\ → \\\\、\n → \\n、\r → \\r，反斜杠先行)
}
```

USAGE 面：用法区 `jh --session=<id> [--session-view=<view>] 只读检视会话日志(五视图投影,零写路径;无 key 可跑)`；`--resume=` 行标「续跑写侧」；flags 区 `--session=<sessionId>`（互斥矩阵）与 `--session-view=<view>`（词表 + 缺省 turns + 仅与 --session 同用）；退出码 3 行「--resume 或 --session 的会话不存在或写者锁冲突」。

## 三行映射（设计增量条目 → 实现 → 测试）

| 设计增量（iteration-33.md / 停 1 携带） | 实现 | 测试 |
|---|---|---|
| CLI 最小查询入口：`--session=<id>` + `--session-view=<turns|tools|usage|timeline|messages>`（缺省 turns），纯文本输出 | HeadlessMain：SESSION_VIEWS 常量、parse 两分支 + 词表校验、缺省应用、runSessionView 旁路；SessionViewRenderer 五视图逐行渲染 | HeadlessOptionsTest 3 测（词表/缺省/需 session；互斥矩阵；usage 区分只读检视与续跑）+ HeadlessSessionViewTest 3 测（keyless 检视真会话落盘、未知 id exit 3、写者锁冲突 exit 3）+ SessionViewRendererTest 8 测（五行视图逐行钉死 + 未知视图 fail loud） |
| ① --help 与互斥矩阵显式区分 --session（只读检视）与 --resume（续跑写侧）；② 互斥面沿 --sessions 先例（与任务文本/--resume/--verify 互斥） | USAGE 用法区/flags 区文案 + parse 校验块五条 + run() 旁路位置 | HeadlessOptionsTest.sessionFlagMutexMatrix + usageDistinguishesReadOnlyInspectionFromResume |
| 零新事件、零写路径、只走 load 读侧 | runSessionView 只 load + 渲染；无 append/无 key 检查/无 agent | HeadlessSessionViewTest.sessionViewReadsOnDiskSessionKeyless（无 key exit 0、stderr 空）+ busySessionInspectionExitsThreeWithoutWriting |
| moditect 登记（停 1 预告延至此停） | dist/jh/pom.xml `<module>io.javanatic.harness.workbench.projection</module>` | jlink 重建后真实会话五视图实录（§4） |
| 12 §6 CLI 清单同步（新 flag 入表 + 退出码表） | 12-api-stability.md：冻结清单 + `--session` 只读旁路句 + 退出码行 3 + 占用拒绝 bullet | 文档同提交（本批） |

## 偏离清单

| 出现什么 | 为什么 | 待裁决 |
|---|---|---|
| 检视成功 exit 0 未单列退出码行（12 §6 表行 0 未提 --session） | 只读旁路成功即 0 的自然语义（--sessions 先例同）；失败面（3）已入表 | 无 |
| M3 首版突变（`写者锁冲突X`）未杀测试 | contains 子串仍命中——突变选择须使断言词面整体消失，换 `写锁冲突` 后必红 | 无——证据档已记 |
| 核心 `Session.create` 空 seed 边缘（B34） | 停 1 已挂账（第二次现形），投影 isEmpty 短路兜住；本停不触 core | 挂账 B 行（已落盘），不阻塞 it33 |
| 00-overview §2 模块总览图未加 workbench | 该图是粗粒度示意（本就不列 jobs/delegation/sandbox/session/interaction/lsp/web/mcp/skill/dist 等组），完整清单权威在 02（已同步 workbench 组 + 依赖图边） | 无 |

## 为什么是这个形状 / 若错靠什么发现

- **为什么旁路在 run() 的 key 检查之前**：检视是纯读侧事实呈现，与「要跑什么」无关；key 检查在旁路之后 = keyless 检视成立（聚焦测试无 key 跑 exit 0 钉死）。
- **为什么缺省在 parse 应用**：run 只收已校验值（仿 provider/model 先例）；词表校验也在 parse——非法视图 fail loud 于用法错误层（exit 2），不进检视层。
- **为什么失败词面与 --resume 同源**：id 不存在与写者锁冲突的语义完全同构，复用 unknownSessionText（重载 retryFlag 参数）与 writerLockCause；词面经 static helper 提取供测试钉死（LOG 走真实 System.err 的既有先例）。
- **若错靠什么发现**：36 聚焦测试逐行钉死渲染契约 + 3 突变（渲染头行/缺省应用/失败词面）必红 + validate/checkstyle 0 违例 + verify 计数 8/8 + jlink 真实会话五视图（1593 事件委派树乱序结果按 callId 正确配对 = 停 1 配对键裁决的实弹验证）。

## 停 2 携带要求核对（用户评审清单）

1. 读/写 flag 区分 ✓：`--help` 用法区（`--resume=` 行标「续跑写侧」、`--session=` 行标「只读检视」）+ flags 区互斥矩阵。
2. 12 §6 CLI 清单同步 ✓：冻结清单 + 旁路句 + 退出码行 3 + 占用拒绝 bullet（本批同提交）。
3. 四件套全勾 ✓：headless 依赖 + moditect + 敌意测试面不动（全量回归）+ verify 计数不变（8/8）。
4. 真实会话验收 ✓：双臂夹具（~/.harness/sessions 82 会话含委派树）跑五视图——主会话 1593 事件 + 委派子会话 + aborted 多轮 + error 工具全实录（[stop2-evidence.md §4](stop2-evidence.md)）。你的那份：`dist/jh/target/jlink-image/bin/jh --session=<id> [--session-view=<view>]`（id 用 `--sessions` 取）。
