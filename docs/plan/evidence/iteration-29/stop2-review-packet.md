# it29 停 2 review packet：Java LSP Minimal Loop Provider（lsp/jdtls）

- 基线：`34eaf49`（停 0/1 已提交）；本停点工作区**未提交**（按停点要求，等点名后收口）
- 范围：`lsp/jdtls` 新模块（11 主源码 / 13 测试类 + Fixtures + 背压夹具 server + 真机探针）；
  `lsp/pom.xml` 注册；`integration/verify_stop2_mutations.py` + `verify-stop2-mutations.sh`
  + `verify-stop2-real.sh`；`docs/plan/{boundary-register,current-state,iteration-29}.md` 同步
- 依赖：无新第三方坐标（jackson 已在根 BOM 管理；夹具复用现有依赖）
- 评审重点（用户点名）：**① 四态真机区分**；**② 「拉取时点无诊断」措辞纪律**
- 本包即 §11 预告产物：本设计、聚焦测试汇总、突变汇总、真实 JDT LS 证据跑（逐轮）、
  四态真机取证、措辞纪律自检、偏离对照勾选。**不 commit、不进 Provider→Consumer 串接。**

## 0. 五行 TL;DR

1. **真实 JDT LS 证据跑全绿**：生产 `JdtlsProvider` 接钉定真机（1.63.0 / 202610191322），
   3 轮启停每轮 `PUBLISHED_DIAGNOSTICS`（round 1 复现停 0 类型错误 code 16777233），
   每轮 close 证据行 exit=0、stdoutEof=true、专属组、unanswered=0、pending=0、ioThreadsStopped=true。
2. **四态真机区分**：PUBLISHED（类型错误 code 16777233 + warning-only code 268435844 双证据）、
   Stale（锚不同）、NotReady（INITIALIZING / CLOSED）、TimedOut（期限=窗口的确定性下界，见 §7 偏离注）
   全部真机命中；清洁文件真机实测得到 PUBLISHED 空数组（items=0），按设计 §7 预案如实记录，
   QUIET_WINDOW 区分由聚焦夹具测试承担（见 §6）。
3. **措辞纪律零违例**：主源码 / 测试 / 设计 / 证据 grep 无「项目无错误 / 分析完成 / 诊断已同步 / 真零」
   正面结论；QUIET_WINDOW 证据行逐字用固定模板（§6）。
4. **门禁三面齐绿**：7 突变注入必红 → 字节级还原复绿；focused EXIT=0；validate EXIT=0；
   full 层全 reactor **304/0/0**（其中 lsp/jdtls 177/0/0，13 个测试类逐类 0 失败）。
5. **遗留与诚实记录**：TimedOut 配方取 deadline==window（设计 §7「略大于」在 warm 同步下余量耗不尽，
   回 Observed——改取契约允许的等长下界，见 §7）；真机 serverVersion 为 OptionalInt.empty（D3 路径）；
   探针 post-close 措辞「capture missed」已修（正面表述「no live launcher child」）并重跑取证；
   state D 立即 pull 首轮真实测得 TimedOut(PT1S)——本机 warm handshake ≈1.0–1.5s，1s 预算下
   「initialize 未完成」前提不成立（实现按契约走正常观察路径、窗口内到期），探针预算收窄 200ms
   使前提确定成立后重跑（§7）。

## 1. 本设计

- 设计文档：[stop2-provider-design.md](stop2-provider-design.md)（停 2 开工时落盘；§1–§11）
- 实现义务落点（抽查）：§4.2 关闭序列八步 → `JdtlsProvider.close()`；§5.1 串行观察区 →
  `observe()` 内部 Call 账本；T5 边界 1/2 → `LspPeer` 私有 transport + `LspFrameReader` 字节计长；
  边界 3（D1–D4）→ `LspPeerTest` 四用例（§7 对照）。

## 2. 聚焦测试汇总

- 模块测试类 13 个 + Fixtures（读写辅助）+ PeerBackpressureServer（吞 TERM 夹具对端）+ JdtlsRealProbe
  （S5 探针，类名非 *Test，surefire 默认包含规则不匹配、不扰计数）。
- full 层 lsp/jdtls 模块汇总 177/0/0，逐类（surefire 计数）：

| 测试类 | 数 | 覆盖面 |
|---|---|---|
| LspFrameReaderTest | 15 | 字节计长、UTF-8 多字节、畸形 header 流级断裂、8 MiB 帽、残帧 |
| LspPeerTest | 16 | D1–D4 矩阵、退出排水、未知请求 -32601、progress 通知、无 version 诊断 |
| LspPeerProtocolTest | 19 | 应答形状非法（错误 id / 双成员 / 坏 envelope）＝ PROTOCOL |
| LspPeerPublicationTest | 5 | 发布锁下原子截点、末值胜 |
| LspPeerBackpressureTest | 15 | 候选缓存帽、溢出=PROTOCOL、背压 |
| SessionTest | 1 | 专属进程组 pgid 读取 |
| JdtlsLaunchTest | 11 | 启动档案输入面（B26）校验 |
| ProjectHashTest | 6 | hash 输入清单（pom+src 字节合成） |
| DiagnosticsCacheTest | 7 | 每 URI 末值胜、generation 换代失效 |
| JdtlsProviderTest | 24 | 观察算法、四态构造、close 幂等/拒新/清 pending、R3-1/2/3/4 修复用例 |
| JdtlsProviderSamplingTest | 10 | before/after 采样线程登记与 close 竞态、线程收束 |
| JdtlsProviderBudgetTest | 11 | 期限预算、TimedOut 边界、NotReady 语义 |
| JdtlsProviderProtocolTest | 37 | 失败分档（CONNECTION/PROTOCOL/IO）、REQUEST_RESPONSE 不可合成 |

- 证据：[stop2-s4-focused-validate-full-green.txt](stop2-s4-focused-validate-full-green.txt)
  （focused EXIT=0 含工作树审计 + 生命周期收尾复验；validate EXIT=0；full 层 7 模块合计 304/0/0）。

## 3. 突变汇总

脚本 `integration/verify_stop2_mutations.py`（thin wrapper `verify-stop2-mutations.sh`），
MUTATIONS 表 7 项，每项破坏必红（exit≠0 且有 FAILURE!/ERROR! 标记）→ 注入后逐字节还原校验 → 复绿；
r3 轮 7/7 达标。证据：[stop2-s4-mutations-green.txt](stop2-s4-mutations-green.txt)。

| # | 突变 | 红（例） | 绿 |
|---|---|---|---|
| M1 | R3-1 同步账本退到 flush 之后 | 2/2 失败 23.4s | 2/0/0 |
| M2 | R3-2 溢出检查移到 Stale 之后 | 1/1 失败 | 1/0/0 |
| M3 | R3-3 采样登记互斥摘除 | 1/1 失败 | 1/0/0 |
| M4 | R3-4 validateInitialize 整体摘除 | 8 跑 7 失败 | 8/0/0 |
| M5 | 掩码恢复（checkLifecycle→checkState） | 8 跑 7 失败 | 8/0/0 |
| M6 | Fixtures 恒 null 上界（保留读面） | 1/1 失败 10.3s | 1/0/0 |
| M7 | 背压夹具能力声明摘除 | 4 跑 1 失败 3 错误 | 4/0/0 |

诚实记录（同证据档）：r1 中 M6 红被 spotless 拦（删读面致 Files 导入未使用）；r1 后首轮重跑在
M1 红阶段崩溃（MVN 未 export → 脚本回退 PATH 无 mvn），M1 突变残留在工作树（MD5 c2f86456…），
r2 因此 M1 失配、M7 绿受污染；r2 改法一（摘导入）致 catch(IOException) 不可达编译错；
最终修法（M6 保留 exists+readString 读面恒 null）+ /tmp 快照还原核验 MD5 848b0dbd… 后 r3 全绿。

## 4. 真实 JDT LS 证据跑（逐轮）

- 入口 `integration/verify-stop2-real.sh`（thin wrapper 固化类路径 + 钉定参数）；
  证据目录 `/Users/abel_yang/study/deepseek/jh-it29-stop2-20261030/`（probe-evidence.txt 为最终源码输出，
  probe-evidence-first-run.txt 为初版过程记录：state D 1s 预算竞态 + post-close 旧措辞，见 §7）；
  run/evidence/ 落每态源文件快照（fixture-baseline-App.java / state-App.java / state-Clean.java）。
- 钉定：server-1.63.0（launcher org.eclipse.equinox.launcher_1.8.0.v20260804-1928.jar）
  + config_mac_arm + 停 0 夹具工作区复制（只取 pom.xml + src）；暖 .m2 供入；零联网。
- 探针退出码 0，尾部 `== verdict: ALL ASSERTIONS PASSED`。

逐轮（每轮独立启停，close 证据行四要素逐轮复验）：

| 轮 | pid/pgid | pull 结果 | close 证据行 |
|---|---|---|---|
| 1（冷） | 59388/59388 | Observed PUBLISHED seq=65 items=1 codes=[16777233] | exit=OptionalInt[0] stdoutEof=true dedicatedGroup=true unanswered=0 pending=0 ioThreadsStopped=true |
| 2 | 61082/61082 | Observed PUBLISHED seq=57 items=1 codes=[16777233] | 同上四要素全达标 |
| 3 | 61846/61846 | Observed PUBLISHED seq=57 items=1 codes=[16777233] | 同上四要素全达标 |

每轮 post-close：`jhThreads=0 no live launcher child (residual-free)`（children 有界扫描无 launcher 子进程 = 无残留）。
3 轮 pgid==pid（perl setsid 专属组）；round 1 复现停 0 类型错误 code 16777233；serverVersion 每轮
OptionalInt.empty（1.63.0 真机发布批次无 version 字段，D3 路径实录）。

## 5. 四态真机取证

| 态 | 真机结果 | 断言 |
|---|---|---|
| A（类型错误） | Observed PUBLISHED items=1 codes=[16777233] | ☑ 非空 PUBLISHED + code 16777233 |
| A2（warning-only） | Observed PUBLISHED items=1 codes=[268435844] | ☑ 非空 PUBLISHED（停 0 夹具未使用导入警告复现） |
| B（清洁用例 Clean.java） | Observed PUBLISHED items=0 codes=[] | ☑ 按设计 §7 预案如实记「LS 实际发布空数组 → PUBLISHED_DIAGNOSTICS」，不改造证据凑 QUIET_WINDOW |
| C（窗内外部改写） | Stale before/after：generation 0→1、projectHash 与 document sha256 均不同 | ☑ Stale 前后锚不同 |
| D（未就绪） | immediate(200ms)=NotReady(INITIALIZING) → warm=Observed PUBLISHED → close OK → after-close=NotReady(CLOSED) | ☑ INITIALIZING 与 CLOSED 双真机命中 |
| E（期限） | warm=Observed PUBLISHED；tight(30s/30s)=TimedOut(PT30S) | ☑ TimedOut 与 PUBLISHED 严格区分 |

四态区分无失败；QUIET_WINDOW 真机无实例（didOpen 后 JDT LS 必发 ≥空数组批次），区分由夹具测试承担（§6）。

## 6. 「拉取时点无诊断」措辞纪律自检

- 账面三条件口径（设计 §8）逐字落地：
  `LspService`「空结果仅称“拉取时点无诊断”」、`LspResult`「空诊断只称“拉取时点无诊断”，不能称
  “项目无错误”」、`TimedOut` 注释「总期限耗尽，不是无诊断」。
- grep 禁令面（主源码 + 测试 + 设计文档）：
  `grep -rn "项目无错误\|分析完成\|诊断已同步\|真零"` → **0 命中**（风险说明文字不在禁令内）。
- QUIET_WINDOW 证据行固定模板（设计 §8 末行）在探针 state B 分支逐字实现：
  「拉取成功（hash a…a）、项目代稳定（前后锚相同）、完整等待窗（elapsed ≥ required），
  拉取时点无诊断。」真机实测 JDT LS 对 didOpen 后的清洁文件发布空数组批次 → 真实结果按
  PUBLISHED_DIAGNOSTICS（items=0）如实记录（设计 §7 预案），模板未在真机触发；
  QUIET_WINDOW 区分与模板由聚焦夹具测试承担（JdtlsProviderTest#quietWindowOnCleanDocument、
  #emptyDiagnosticsArrayIsDistinctFromQuiet）。
- 探针自身措辞自检：post-close 原表述「pid unknown (capture missed)」把无残留进程表述为
  「捕获失败」——违反同一纪律，已修为「no live launcher child (residual-free)」，
  用最终源码重跑取证（旧跑仅作过程记录，不作为证据行）。

## 7. 偏离对照勾选

| # | 项 | 状态 |
|---|---|---|
| D1 | shutdown 响应 `{}` 计已应答 | ☑ LspPeerTest#emptyObjectShutdownResultIsAccepted；真机 close 全绿 |
| D2 | initialized 前 create 请求照常答 null、progress 不挂起 | ☑ LspPeerTest#preInitializeServerRequestIsAnswered + #progressNotificationReachesSink |
| D3 | 无 version 批次接受、version 不作锚 | ☑ LspPeerTest#noVersionDiagnosticsReachSink；真机 serverVersion=OptionalInt.empty 实录 |
| D4 | 退出尾流请求应答到流关/宽限到期 | ☑ LspPeerTest#exitPhaseServerRequestIsAnswered；真机 close unanswered=0 |
| 生命周期 ≥3 启停 | 逐轮 EXIT/EOF/pending/线程/专属组 | ☑ 真机 3 轮 + 6 态 close 证据行（§4/§5） |
| TERM→KILL 升级 + 复验 | 夹具驱动同一关闭实现 | ☑ JdtlsProviderTest 收尾复验（owned pid exit=137/0）+ SessionTest |
| TimedOut 配方偏离 | 设计 §7「略大于窗口」→ 实现取 deadline==window | ☑ 契约允许（窗口 ≤ 期限）；warm 同步下「略大于」余量耗不尽回 Observed，
  取等长保证终检必然过期；已按设计 §7 末句「诚实记录」登记（探针注释 + 本行） |
| state D 立即 pull 竞态 | 探针初版 1s 预算断言 INITIALIZING | ☑ 首轮真实测得 TimedOut(PT1S)：本机 warm handshake ≈1.0–1.5s，1s 预算下
  「initialize 未完成」前提不成立，实现按契约把已完成初始化的观察走正常路径、在窗口内到期 → TimedOut。
  非 Provider 缺陷；探针预算收窄 200ms（<< 进程 spawn + JVM boot 下限）使前提确定成立，重跑取证 |
| Windows 专属组 | 直启 + 披露 | ☑ 设计 §4.1 明示；本机 macOS perl setsid 包装器（真机 evidence 行 dedicatedGroup=true） |
| 真实跑 serverVersion | 1.63.0 真机发布批次无 version 字段 | ☑ 每轮 OptionalInt.empty 实录，不作锚（D3） |

## 8. 停下等点名

- 本停点工作区未提交（含 S1–S5 全部产物）；收口批（提交 + 05 回填 + 边界表）等用户点名后执行。
- 不自动进 Provider→Consumer 串接、不动装配面；评审重点四态真机区分 / 措辞纪律按点名执行。
