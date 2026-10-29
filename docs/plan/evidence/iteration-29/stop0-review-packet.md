**不变量增量**：仅验证独立裸进程；JH 零实现。固定 build、输入 hash、真实诊断与退出证据齐备，但输入锚不冒充诊断来源锚。
**裁决点**：停 0 工程闭环是否有限通过；T1 版本关联仍阻塞，T2 权限与 T3 生命周期待裁；不自动进入停 1。
**风险自报**：诊断无 version、无空数组、初始化顺序偏离、shutdown `{}`、尾流请求未答；项目写盘、公共下载及宿主 JDK 自动发现均已发生。
**证据入口**：[事实汇总](stop0-jdt-ls-probe.txt)、[实际命令](stop0-real-command-01.txt)、[运行结果](stop0-run-01/result.txt)、[协议尾流](stop0-run-01/protocol-tail-analysis.txt)。
**若只能改一处**：先证明诊断对应哪个源码 / 项目版本；证明不了就保持未就绪或超时，不用“当前 hash + 到达顺序”包装成功。

# Stop 0 — JDT LS Reality Review

## Verdict Requested

**建议：裸进程工程闭环有限通过，候选保留；诊断语义与实现开工不放行。** 这是审查建议，不是用户已通过记录。首次真实运行已结束，未为获得空诊断或消除偏离而重跑；不擅自切换 compiler API。

本包核验范围是一个 Maven 模块、一个 JPMS 模块、Java25 final 语法及两文件正例。真实类型错误、跨文件定义 / 引用和正常退出均有证据；没有证明 JH 全仓兼容、通用新鲜度、真零诊断或完整生命周期故障矩阵。

| 停 0 必需观察 | 实际证据 | 判定边界 |
|---|---|---|
| 具体 build | 官方 milestones `1.63.0 / 202610191322`；归档 SHA、manifest、commit 与运行日志吻合 | `serverInfo=1.63.0-SNAPSHOT`、`eclipse.buildId=unknown` 原样保留，不称另一个版本 |
| initialize → initialized | journal seq47 响应、seq48 通知；启动到响应约 3.855 秒 | 不是项目导入 / 分析完成屏障；存在初始化前消息偏离 |
| didOpen → 真实诊断 | seq379：`int → String`，severity 1，code `16777233`，零基 `[4:23,4:25)` | 已知夹具正例，诊断无 `version`，不泛化为生产来源证明 |
| 更新观察 | seq400：旧类型错误不在全量结果中，仅未用 module import 警告 | 约 0.423 秒已收到更新；等 `[]` 90 秒超时，**不是无更新，也不是真零** |
| 定义 / 引用观察 | definition 指 Calc `[3:22,3:27)`；references 含 App `[5:20,5:41)` 及声明 | 调用返回整个表达式范围，覆盖 token；未验跨 Maven 模块 / 依赖源码 / 空结果 |
| shutdown → exit / 回收 | seq411 响应 `{}`；seq412 exit；exit0、EOF、自有组消失、无 forced cleanup | 工程退出成功，不是规范 null 或所有服务端请求均已处理 |

## Reproducible Inputs and Evidence

固定工件来源为 [Eclipse milestones](https://download.eclipse.org/jdtls/milestones/1.63.0/jdt-language-server-1.63.0-202610191322.tar.gz)。大小 `51143565 bytes`，SHA-256：

```text
b1f2abe0b3cccb462cf64fb4a5587c7d09df60cf8be7748b152cbd89d018bdc4
```

launcher `1.8.0.v20260804-1928`，server bundle `1.63.0.202610191322`，commit `d98abba8f04cf041542d9ebb6df0d472a1395606`。内部版本标识 `1.63.0-SNAPSHOT` 不被改写。[工件核验](stop0-artifact-verification.txt)与[运行日志](stop0-run-01/jdt-ls-workspace-log.txt)分别证明静态件和实际启动件。

运行时间：2026-10-29 `07:05:01.541728Z` 至 `07:08:15.919116Z`。[命令记录](stop0-real-command-01.txt)的 Python 客户端退出码为 0；Java argv、白名单环境、JAR hash、PID / PGID 均在 [result](stop0-run-01/result.txt)，不以文字模板冒充执行。

探针运行时 SHA-256：`6c1213de4b50bb04128173cd484775686a537ec8426b8fde6052213ba5509fe6`。运行环境为 Temurin `25.0.4.1+1-LTS`、macOS26.2 arm64、Python3.12.6。LS 解包缓存中的 Maven core 为 3.9.16，不是宿主 Maven3.10.0。

| 证据组 | 已落盘入口 | 证明什么 / 不证明什么 |
|---|---|---|
| 一手依据与下载 | [固定源码 / LSP3.17 研究](stop0-source-research.txt)、[续传核验](stop0-download-ranges-01/summary.txt)、[工件检查](stop0-artifact-verification.txt) | 官方来源 / 完整 SHA / 内嵌 manifest；研究提案不是实跑结果 |
| 夹具与输入锚 | [实际夹具](stop0-fixture-ready.txt)、[合法 Java25 编译](stop0-fixture-legal-check.txt)、[已知坐标](stop0-fixture-expectations.txt)、[启动前快照](stop0-runtime-before.txt) | 单 Maven + JPMS、无 preview、无业务依赖；不覆盖研究提案中的双 Maven 模块 |
| 客户端红 / 绿 | [协议红](stop0-client-contract-red.txt)、[EPERM 红](stop0-client-zombie-red.txt)、[双入口绿](stop0-client-authorized-green-02.txt) | 四项单测及 fake LS 集成；不是 JDT 语义证据 |
| 突变及复绿 | [8 项突变](stop0-client-mutations-round2.txt) | 8/8 被杀、原字节未变、最终双入口 EXIT0；不是 8 次真实 LS 运行 |
| 真实运行 | [完整命令](stop0-real-command-01.txt)、[JSON 语义 journal](stop0-run-01/protocol.txt)、[result](stop0-run-01/result.txt)、[独立 stderr](stop0-run-01/server-stderr.txt) | 实际启动 / 诊断 / 定位 / 退出；journal 不是全程 wire 原字节 |
| 尾流 | [raw hex](stop0-run-01/stdout-after-shutdown.txt)、[解码 / 初始化顺序](stop0-run-01/protocol-tail-analysis.txt) | 1209 bytes、7 条消息，其中请求 id `19` 未回复；没有逐帧时间 |
| 设置与写入 | [生效设置](stop0-run-01/effective-settings.txt)、[before](stop0-run-01/directories-before.txt)、[after](stop0-run-01/directories-after.txt) | 生成 prefs 支持设置生效，五目录前后文件差异；不是全机读写 / 网络审计 |
| 回收与复核 | [post-exit](stop0-run-01/post-exit-check.txt)、[首轮独立审查归纳](stop0-review-round1.txt) | 自有 PID / PGID 与数字 ps 快照为空；审查归纳不冒充新执行日志 |

客户端两个自检入口的实际命令、关键输出与 `EXIT=` 均在上表证据中。直接执行 selfcheck 文件只跑集成，不能当成四项 unittest 也已执行。旧失败日志、前置拦截及恢复记录保留，不把过去的红改写成绿。

## Three-line Traceability

| 设计增量（探针层，不是已落产品设计） | 承载实现 | 验证 |
|---|---|---|
| P1 固定候选 / 隔离输入 / 有界协议，不偷建 JH 模块 | [stop0-probe.py](stop0-probe.py) `run_probe` L171–241；`Peer.send/receive/reply/request` L61–147 | 工件 / runtime / settings 证据；注册、shutdown 用例及突变 |
| P2 错误 buffer 与合法磁盘分离；未知来源不补盖为当前 | `run_probe` L242–260；`Peer.diagnostic` L149–168 | missing-array / disk-mutation 突变；真实 seq379/400；输入 hash 与 disk 未变 |
| P3 专属组 / 有界排空 / 回收 / 写入对账，尾流限制如实 | `group_alive` L20–28；`run_probe` L274–339 | EPERM 最小观察、1MiB fake 尾部、wait-before-drain 突变、真实 exit0 与 post-exit |

## Contract-bearing Source

以下摘录与本次实际运行的探针一致；完整文件见 [stop0-probe.py](stop0-probe.py)。仅作为一次性实验客户端审查，不提升为未来 Provider API。

### Diagnostic Observation (L149–168)

```python
    def diagnostic(self, uri, after, empty, timeout=180):
        deadline = time.monotonic() + timeout
        while True:
            for entry in self.frames:
                message = entry['message']
                if entry['direction'] != 'server' or entry['seq'] <= after:
                    continue
                if message.get('method') != 'textDocument/publishDiagnostics':
                    continue
                params = message['params']
                if params.get('uri') != uri:
                    continue
                diagnostics = params.get('diagnostics')
                if not isinstance(diagnostics, list):
                    continue
                if empty and not diagnostics:
                    return entry
                if not empty and any(d.get('severity') == 1 and d['range']['start']['line'] == 4 for d in diagnostics):
                    return entry
            self.receive(deadline)
```

`after` 只用于实验时序筛选，**不是版本 fence**；severity / line 筛选也不能替代人工核验 code、message、range。本次人工与原帧核验为真实类型错误；若直接把这个方法搬成生产诊断成功判据，会违反 T1。

### Disk / Buffer Split (L242–260)

```python
            updated = app.read_text()
            assert updated.count('String label = "ok";') == 1
            text = updated.replace('String label = "ok";', 'String label = 42;')
            state['disk_source'] = {'sha256': digest(app), 'text': updated}
            opened = peer.notify('textDocument/didOpen', {'textDocument': {'uri': uri, 'languageId': 'java',
                                                                        'version': 1, 'text': text}})
            state['phase'] = 'diagnostic-v1'
            state['source_v1'] = {'document_version': 1, 'sha256': hashlib.sha256(text.encode()).hexdigest(), 'text': text}
            state['diagnostic_v1'] = peer.diagnostic(uri, opened['seq'], False)
            state['diagnostic_v1_seconds'] = round(time.monotonic() - start, 3)
            state['phase'] = 'change-v2'
            changed = peer.notify('textDocument/didChange', {'textDocument': {'uri': uri, 'version': 2},
                                                            'contentChanges': [{'text': updated}]})
            state['source_v2'] = {'document_version': 2, 'sha256': hashlib.sha256(updated.encode()).hexdigest(), 'text': updated,
                                  'did_change_seq': changed['seq']}
            try:
                state['diagnostic_v2'] = peer.diagnostic(uri, changed['seq'], True, timeout=90)
            except TimeoutError as error:
                state['observations']['clear_diagnostics_gap'] = str(error)
```

v1 hash `625220486314589bb34900e1bd21808afb7508f52fea734f17f85e6bb0529f84`；disk/v2 hash `d8efc20eb5f73ada640e00a3c8499bc674ecc62cdef194cb3e614fe4af06e130`。这证明客户端送了什么，不证明无版本通知因果归属于哪个项目版本。

### Shutdown and Drain (L274–299)

```python
            state['phase'] = 'shutdown'
            state['shutdown_response'] = peer.request('shutdown', None, timeout=20)
            peer.notify('exit')
            process.stdin.close()
            tail = bytearray(peer.buffer)
            drain_deadline = time.monotonic() + 20
            state['stdout_eof'] = False
            while time.monotonic() < drain_deadline and peer.selector.select(max(0, drain_deadline - time.monotonic())):
                chunk = os.read(process.stdout.fileno(), 65536)
                if not chunk:
                    state['stdout_eof'] = True
                    break
                tail.extend(chunk)
                if len(tail) > 8 * 1024 * 1024:
                    raise ValueError('unexpected oversized stdout after exit')
            state['exit_code'] = process.wait(timeout=max(0.01, drain_deadline - time.monotonic()))
            state['total_seconds'] = round(time.monotonic() - start, 3)
            (evidence / 'stdout-after-shutdown.txt').write_text(json.dumps({'raw_hex': tail.hex()}) + '\n')
            # wait 已回收本子进程；再检查其专属进程组，不按名称触碰别的 Java 进程。
            try:
                os.killpg(process.pid, 0)
                state['process_group_gone'] = False
            except ProcessLookupError:
                state['process_group_gone'] = True
            state['core_gate'] = state['exit_code'] == 0 and state['process_group_gone'] and state['stdout_eof']
            state['phase'] = 'finished'
```

这里排字节而非继续调用 `Peer.receive`，因此退出尾流请求 id `19` **未回复**。journal 内 18 个服务端请求有回复，不得推广为总共只收到 18 个或全部回复。尾流含 shutdown 响应时已缓冲字节，不能重建每条发送时间。`core_gate` 不是严格协议合规 / T1 / 定义引用的综合验收器。

失败清场承载段为 `run_probe` L303–320：仅对自有组 TERM → 有界等待 → 必要时 KILL → wait / 复验；实际真跑没有进入该分支。下列 EPERM 判定（L20–28）防止把“不确定”误标成“组消失”，不声称所有异常清场分支已经实测：

```python
def group_alive(pgid):
    try:
        os.killpg(pgid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        # macOS 未回收的已终止组可报 EPERM；不把不确定当成已消失，交调用者 poll/wait。
        return True
```

## Deviations and Decisions

| 出现什么 | 原因 / 当前解释 | 待裁或限制 |
|---|---|---|
| 研究提案双 Maven 模块，实际只跑单模块 + JPMS | 停 0 使用最小可证伪夹具，未实施研究提案全部扩展项 | 不称跨 Maven 模块通过；后续验收范围须明确 |
| 无 `version`、无 `diagnosticProvider` | client 声明 versionSupport 不强制 server 提供；本次未尝试协议 pull | T1 不得用“逻辑拉取”偷换成存在同步分析屏障 |
| seq400 仍为警告 | Java25 `import module java.base` 合法，但未被使用 | 保留警告；没有 `[]`，不关 warning / 改夹具重跑凑绿 |
| initialize 前 progress/create、progress、language/status | 固定候选实际顺序；模板未提供 initialize workDoneToken | T5 显式决定兼容还是拒绝；不能叫严格 LSP3.17 合规 |
| shutdown `{}`，尾流 id19 未答 | server 返回对象；客户端关闭 stdin 后只做 raw drain | T3/T5 须定义收尾策略；不得静默归一化原证据 |
| 项目 / 配置 / 缓存 / 索引写入，公共下载 | Maven 导入、自动构建、私有 repo 冷启动 | T2 可信 server 非只读进程；停止不回滚文件 |
| launching prefs 含宿主 Java8 / AppletPlugin | LS 自动发现已安装 JVM | HOME 隔离不是宿主访问隔离；默认和项目仍指定 Java25 |
| stderr incubator / Unsafe 警告 | ALL-SYSTEM、Guice/JDK25 兼容提示 | 不是项目 preview；启动参数权限不能无审查推广 |

规范依据见[固定研究](stop0-source-research.txt) §4、§7.4，分别对应 [LSP initialize](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#initialize) 与 [shutdown](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#shutdown)。

## T1–T3 Review Proposals (Not Approved)

### T1 — Diagnostic Semantics: Still Blocking

保留用户硬要求：未就绪 / 过期 / 超时 / 真零诊断可区分，正常非空也必须有来源锚。**建议不批准以当前 push 缓存形状开工。** 当前仅证明已知输入能触发预期诊断，没有证明无版本通知的可靠来源关联，也没有项目模型 / 依赖变化的完成屏障。

可以裁定的保守语义：无可证明来源的候选保持“未就绪”，到 deadline 记“超时”；“过期”只适用于来源锚已知且旧于目标的结果，不把无法归属随意叫过期；“真零”必须同时有目标版本完成证据和空集合。非空错误也不能例外补盖当前 hash。

尚不能裁为已解决的方案：仅等待静默窗口、Ready/ServiceReady、发送 didChange 后取下一条、对当前磁盘 hash 做标记、用请求 id 冒充分析版本。这些都不能消除旧任务迟到与项目模型变化。

下一步若另获授权，应先补一条**可证明的版本关联路径**及其拒绝用例，再定 Provider 形状；不预先宣称某个 JDT 扩展或新进程方案一定成立。若无法满足，正式重裁方案或范围；compiler API 只直接提供诊断，不自动补足定义 / 引用，不暗中替代。

### T2 — LS Permissions: Explicit Trust, Not Read-only

建议待裁边界：启用 LS 是独立的可信外部进程授权；显式工件 / Java / workspace / index / config / cache / 环境参数，禁止静默用用户私有配置兜底。工具调用治理继续存在，但**拒批工具调用不撤销已经发生的 server 启动 / 导入副作用**。本包不新增审批 API 或产品配置。

本次实际五目录常规文件快照：新增 **356 = project9 + index41 + home243 + config63 + tmp0**，修改 0，删除 0；计数来自 before/after 的路径集合与 bytes/SHA，不是全机、瞬时文件或网络流量计数。

项目新增 `.project`、`.classpath`、四个 `.settings/*`、三个 `target/classes/*.class`；源码未变。双 Maven settings 在 prefs 中明确指私有 XML；空私有仓产生插件及依赖缓存，offline=false 的联网确实发生。原 server 解压树 hash 未变。

另外，LS 输出记录了宿主 `/Library` 中 Java8 / AppletPlugin 安装路径；主代理未主动读取用户凭据或扫描这些宿主路径来补证，LS 的实际访问未做全机审计，不声称已证明 LS 从未读取凭据。本次继承白名单与私有 HOME **不构成 OS 隔离**，不能承诺只触及指定目录。两项 XML entity size limit=0、ALL-SYSTEM / add-opens 是探针启动参数，不应未经审查当通用安全默认。

### T3 — Lifecycle: Normal Exit Proven, Fault Contract Pending

建议起点（待裁）：会话显式拥有 server，workspace 与 index 绑定且独占；不共享无主全局实例，不自动无限重启。请求取消和进程关闭分别定义；崩溃 / 重启使旧 generation 的结果不可采纳。关闭必须停接新请求、收敛 pending、执行有界退出与定向强退兜底；文件写入不回滚。具体公开类型、scope 接线和锁实现仍未定。

本次冷启动约 **103.478 秒**才见已知类型错误（含首次导入 / 下载）；initialize 约3.855秒不是 ready。总时长194.196秒含90秒空诊断观察；shutdown 到回收约0.63秒，只是单次近似值，不作为默认超时或性能指标。

尾流请求未答以及 shutdown 非规范返回必须进入产品收尾契约与测试，而不是简单复制探针。当前未覆盖真实 LS 的请求取消、崩溃 / 强退、重启、scope 关闭、多实例锁冲突，亦未证明任意脱组后代或其他 OS 的回收。post-exit 是自有 PID / PGID 与数字 ps 的快照，不是全生命周期进程追踪。

## Why This Shape / How Failure Is Detected

先做独立标准库客户端，让候选是否能启动、导入、产出语义结果和退出不依赖 JH 抽象。合法磁盘 / 错误 buffer 的对照减少“只读取磁盘旧错误也假绿”的解释；真实内容与坐标核验补足脚本粗筛。工件 / 脚本 / 源码 hash 防止同名件或输入漂移，不能拿它们替代 server 来源证明。

反例测试证明缺数组不当空、未实现注册不假成功、setup 错误不假绿、stdout 堵塞不先 wait、EPERM 不当回收成功；真实 LS 则揭示替身无法覆盖的无版本、未用 import、初始化偏离及尾流请求。保留这些差异，而非修饰夹具，才有足够事实裁定 T1–T3。

## Delivery Checks

[首轮出包机械核验](stop0-packet-check-01.txt) EXIT0：归档 / 脚本 hash、实际诊断 / 定位 / 尾流 / 退出字段、写入计数、相对链接目标和 Git 改动面一致。该轮 hash 对应两项措辞澄清前的 packet / iteration 文本；不把它当作修订后文本 hash。核验没有重跑 LS、客户端测试或 Maven，不替代 T 项裁定；当前有未提交 Python 探针，因此没有用 docs 层冒充源码验证。

[最终独立文档审查](stop0-review-round2.txt)未发现 Critical / Important 出包阻断，两项 Minor 均已澄清：私有目录列全；主代理行为与 LS 未经全机审计的边界分开。原始 HTTP 头有9处行尾空格，保留字节；`git diff --check` 只覆盖 tracked/index，不声称未跟踪原始日志已满足未来提交格式门禁。本会话生成的单个探针 `.pyc` 及空 `__pycache__` 已清理，未删除其他文件。

## Handoff Boundary

- 当前仅交付停 0 证据与审查建议。人工停 0 通过、T0–T7 裁定及最终锚点补齐仍未发生；不勾实现验收，不进入停 1。
- 真实运行脚本与原始证据不为改善结果而改写；不再启动第二次 LS。静态出包检查不等于再次执行 JDT 或全仓 Maven。
- 未新增本轮 commit、未 push、未 amend、未发布；当前 HEAD `7c8d6a7`，相对本地 `origin/master` 待推1笔。push 仍等新鲜点名。
- 不读取 / 提交凭据，不改 `.java-version` 或门禁规则，不混 B4 / MCP `_hash8` / Web T9。范围完成也不等于 0.3.0 发布授权。
