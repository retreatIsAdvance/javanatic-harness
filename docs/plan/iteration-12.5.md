# 迭代 12.5 — shell-docker Provider(环境级隔离)（状态：已完成）

模块:shell/docker(新)、sandbox/policy(撤装载期 provider 校验)、shell/bash-local(方言行级匹配)、examples/headless(--docker/--image)、bundle/base(行)
四确认日期:2026-09-12(用户确认提案并附七风险点,全部纳入设计——见下表)

## 四确认(已确认 2026-09-12,附七风险点决策)

- **内容**:
  1. **shell/docker 模块(零新依赖)**:`DockerShellPlugin`(id "shell-docker")提供第二个 `ShellExecutor`——命令经 `docker exec` 在容器内执行,docker CLI 走 ProcessBuilder(不引 docker-java:CLI 是稳定契约);容器 `docker run -d --read-only -v <ws>:<ws>[:ro|:rw] --tmpfs /tmp -w <ws> <image> sleep infinity`,**R3 挂 scope close(rm -f)**;镜像须含 bash+setsid+coreutils kill(文档化)
  2. **沙箱词表的容器同义实现**:挂载面=可写面——READ_ONLY → 根只读+ws `:ro`;WORKSPACE_WRITE → 根只读+ws `:rw`+`/tmp` tmpfs;DANGER → 无 --read-read-only(ws :rw,容器 OS 隔离仍保留);拒绝方言 `Read-only file system`/`Permission denied` 复用 `ShellResult.sandboxDenied` 链
  3. **headless `--docker`/`--image=`**:overlay 禁 shell-bash-local 行、启 shell-docker 行、workspace 钉临时区;默认镜像 ubuntu:24.04
  4. **测试与文档**:无 daemon 自跳过(assumeTrue);有 daemon 的 e2e 矩阵 + 并发 + 泄漏 + create-once;README/02/05 补环境级隔离一段

## 七风险点决策(用户 2026-09-12 评审意见,全部采纳)

| # | 风险点 | 决策 |
|---|---|---|
| 1 | **容器键策略(防串台)** | 键 = **(规范化 workspace, mode)**——不同策略绝不共用容器(挂载面不同);容器名 `jh-shell-<uuid>` 永不按名复用(防跨组合串台);同组合同键并发共享容器 = 预期语义。cwd 须在 workspace 内(fail loud) |
| 2 | **超时击杀链路(容器内 pid vs CLI pid)** | 命令包装 `setsid bash -c 'echo $$ > /tmp/jh-exec/<marker>.pid; exec bash -c "$JH_COMMAND"'`(**命令经环境变量 JH_COMMAND 传输,零转义地狱**;模型内容永不进 wrapper 文本);setsid 使其会话组长 → 击杀 = `kill -TERM -- -<pgid>` 再 `-KILL`,对 pgid 而非 pid(整树);超时/取消同一条主机侧链路(CLI destroyForcibly 兜底);击杀 exec 有界等待,失败不阻塞收尾(rm -f 终将清场) |
| 3 | **恒等 Provider 的 enforcement 值** | **恒等 SandboxProvider 撤销**——confine 契约是「返回的 argv 受限执行」,恒等返回即违约(强制点在容器边界而非 argv,穿这层接口是撒谎)。替代:**撤 sandbox-policy 的装载期 provider 在场校验**(dsh 本就是运行期 fail-closed),bash-local 的逐调用 fail-closed belt(it12 已实现+测试)与 docker 的直接消费即为强制故事;PRODUCTION 探针(档位≠DANGER)不变。it12 文档修正表记契约修订 |
| 4 | **拒绝方言匹配层级** | **行级**(stderr 逐行、大小写不敏感 contains,exit≠0 门控)——与 dsh「within each stderr line」契约对齐;修正 bash-local 现状(整流 contains 会把模型 echo 出的方言串误标)。已知残余:命令自身打印方言文本仍可误标——标记只影响叙述不影响控制流,文档记录 |
| 5 | **探针时机(fail loud vs lazy)** | **apply 期双探针 fail loud**:daemon(`docker version`)+ 镜像在场(`docker image inspect`)——行启用即执行意图,最早可解析点;避免首调被镜像拉取拖住数十秒;daemon 后启/重启窗口的代价文档化(组合启不动 = 诚实信号)。逐调用 belt 仍在(容器创建失败照样 fail loud) |
| 6 | **互斥校验位置** | **kernel 同 scope 重复 provide 即校验**(已 fail loud)——不在 provider 侧加 resolve 守卫:it9 的 per-agent scope 挂载本来就允许子 scope 覆盖父级 ShellExecutor(bash-local@root + docker@agent 是合法形态),resolve 守卫会误杀;headless --docker 按构造产互斥行;base 注释文档化 |
| 7 | **并发 + 容器泄漏测试** | 并发:4 线程同 executor 混合命令——结果按调用配对无串线、输出上限逐调用独立;泄漏:scope close 后 `docker ps -a --filter name=jh-shell-` 为空(多工作区多容器同样清);create-once:同键 N 次执行容器数不增;JVM 崩溃可留孤儿容器(已知限制,`jh-shell-` 前缀可 grep 清理,文档记录) |

- **目标**:环境级隔离成为组合选择(换 ShellExecutor Provider 不动 seam);沙箱三档词表在容器后端同义且更强(整个根文件系统只读,可写=显式挂载面)
- **为什么**:用户指认 docker/k8s/云为沙箱主要形态(it12 拆案 A);0.1.0「敢让人跑」第二档强度;it13 CI 可用容器跑不可信 e2e
- **不做**:k8s/云/microVM(E2B 式 it14+);pwsh(随 it13 CI);容器内叠 Landlock(文档记录可叠);per-session 容器(按 workspace+mode 键,拆分随 subagent);Dockerfile/镜像构建;资源限额(--cpus/--memory 挂账);网络策略;docker-java 依赖

## 验收(证据 = 实际执行的命令与结果)

环境:docker daemon **24.0.6** 在场;JDK temurin-25;构建用 IntelliJ 自带 maven
(本机无系统 `mvn`——见文末「挂账」表第三行)。

- [x] **全 reactor `mvn -B package` 绿**
  ```
  export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
  "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -B -ntp package
  → BUILD SUCCESS
  ```
  **43 reactor 项目 / 56 测试类 / 285 测试 / 0 失败 / 0 错误 / 2 跳过**
  (跳过 = `DeepSeekE2ETest`、`RealModelAgentE2ETest`,keyless 自跳过)。
  计数只取 surefire 的逐类行(`Tests run: … -- in …`),不含各模块汇总行——否则重复计数。
  **测试数记账**:it12.5 净贡献 **+11**(全部来自新增 `DockerShellTest`);
  `SandboxPolicyTest` 是把 `confiningModeWithoutProviderFailsLoudAtLoad` 换成
  `confiningRequestWithoutProviderFailsClosed`(数量不变),`LocalBashExecutorTest`
  是在既有方法内加行级方言断言(数量不变)。本文件提案预测「268 → ~280」,
  实测 285;**it12 记录的 268 用本迭代的计数口径无法复现**,故只报实测值,不硬凑对账。
  无 daemon 时 docker e2e 自跳过(`assumeTrue(daemonUp())`)——本迭代在 daemon 在场下跑,未验证跳过路径。

- [x] **e2e 挂载面矩阵**(daemon 在):`DockerShellTest` **11/11 绿**(18.72 s)
  - WORKSPACE_WRITE 区内写 → `mountSurfaceMatrixWorkspaceWriteAndReadOnly`:exit 0 且宿主侧同卷可见(`in.txt` = `ok\n`)
  - 区内写容器根(`/etc/blocked.txt`)→ exit≠0 + `sandboxDenied` + stderr 含 `Read-only file system`(EROFS)
  - READ_ONLY 下区内写 → exit≠0 + `sandboxDenied`,宿主侧 `ro.txt` 不存在
  - **DANGER 下全开** → 新增 `dangerModeOpensContainerRootAndWorkspace`:容器根写 `/etc/jh-danger.txt` exit 0 + `sandboxDenied=false`,区内写仍落宿主(**提案里没有这条测试,本次补齐**)
  - **偏差(镜像)**:验收项写 `ubuntu:24.04`,但本网络 docker.io 不可达——
    `docker pull ubuntu:24.04` → `Get "https://registry-1.docker.io/v2/": net/http: request canceled while waiting for connection (Client.Timeout exceeded while awaiting headers)`,exit 1。
    改用本机在场的 **`agent-runner:latest`**(实测 Ubuntu 26.04 LTS、bash 5.3.9、`/usr/bin/setsid` 在场),
    满足文档对镜像的同一能力要求(bash + setsid + coreutils kill)。**部署默认拼写仍是 `ubuntu:24.04`**
    (`HeadlessMain` 的 `--image` 缺省值),测试常量旁已注明理由。

- [x] **击杀链路:timeout/abort → 容器内进程组死**
  - `timeoutKillsInContainerProcessGroup`:`sleep 30 & sleep 30` + 1 s 超时 → `TimeoutException`;随后纯 bash 枚举 `/proc/[0-9]*` 证明同容器内无残留 `sleep`(后台子进程同 pgid 一并死)
  - `abortKillsInContainerProcessGroup`(**本次新增**,提案只写了 timeout):60 s 超时 + 700 ms 后另一线程取消 → 抛 `AbortedException`(非 `TimeoutException`),`/proc` 枚举同样无残留
  - 两条触发共用 `killInContainer`(pgid TERM → KILL,CLI `destroyForcibly` 兜底)

- [x] **并发 + create-once + 泄漏**
  - `concurrencyPairedAndCreateOnce`:4 线程 × 4 次 = **16 次**执行(提案写 8 次,实测加倍),stdout 与调用逐一配对无串线,同 `(ws, WORKSPACE_WRITE)` 键容器数恒 **1**
  - `scopeCloseRemovesContainersAcrossWorkspaces`:`(ws1,WW)` + `(ws2,WW)` + `(ws1,RO)` = 3 键 → 3 容器;`rt.close()` 后 `docker ps -aq --filter name=jh-shell- | wc -l` = **0**
  - 全量构建后与两轮真跑后复查:`jh-shell-*` 容器数均为 **0**(R3 经完整 boot/close 路径成立)

- [x] **探针:daemon 停时行启用 → apply fail loud;镜像缺失 → apply fail loud**
  - `daemonUnreachableFailsLoudAtApply`(**本次新增**):注入伪 `docker` 脚本(像真 daemon 停机那样 `version` 非零退出)→ `Plugin failed and rolled back: shell-docker`,cause 含 `docker daemon unreachable`。
    **不停真 daemon**——本机有无关业务容器在跑,停 daemon 是破坏性动作;伪 CLI 走的是与真停机同一条 `exitValue() != 0` 分支。
    为此给 `DockerShellPlugin` 加了 `dockerCli` 构造器 seam,与 `SandboxLocalPlugin(seatbeltBinary)` / `SandboxLocalTest.failClosedWhenBackendUnusable` 同款惯例。
  - `unusableCliFailsLoudAtApply`(**本次新增**):`/nonexistent/docker` → spawn 失败分支,cause 含 `docker CLI unusable`
  - `missingImageFailsLoudAtApply`:`definitely/missing:tag` → cause 含 `image not present`
  - **真 boot 路径复验**(非单测):`HeadlessMain --verify --docker`(缺省镜像 `ubuntu:24.04` 本机缺席)→ exit **1**,
    `IllegalStateException: shell-docker: image not present locally: ubuntu:24.04 — docker pull ubuntu:24.04 (no auto-pull: first call must not stall on the network)`,栈经 `DockerShellPlugin.probeImage → apply → PluginLoader.loadAllUnder`

- [x] **`--verify` 冒烟;真跑(--docker):模型区内写成功、越界(容器根)写拒 + 标记**
  - `--verify`(无 docker,基线)→ exit 0「verify 通过」——docker 行 present-but-disabled 不扰动既有组合
  - `--verify --docker --image=agent-runner:latest` → exit 0「verify 通过」
  - `--verify --docker --image=agent-runner:latest --policy=PRODUCTION` → exit 1,仍如实报「审批为 AUTO」「token budget 为零」两条违规——治理断言未被 docker 行削弱
  - **真跑(DeepSeek,`--docker --image=agent-runner:latest`)**:任务要求两条命令各自单独调用、命令体内不得附加 `echo $?`。exit 0,20 事件落账。
    从 `~/.harness/sessions/headless-1/log.jsonl` **本次运行的行切片**(第 150–169 行)取证:
    - `tool/call` seq 6:`{"name":"bash","arguments":"{\"command\": \"echo hello > inside.txt …"}"}` → `tool/result`:`exit: 0`
    - `tool/call`:`{"command": "echo blocked > /etc/jh-blocked.txt …"}` → `tool/result`:
      `exit: 1 [sandbox: a file effect was denied by the sandbox policy]` + `stderr: bash: line 1: /etc/jh-blocked.txt: Read-only file system`
    - 模型终答原样引用了 `exit: 1 [sandbox: …]` 与该 EROFS stderr —— 叙述链通到模型
    - **宿主侧写穿验证**:`find /var/folders -name inside.txt -newermt "2026-09-13 20:00"` → 两个 headless 临时工作区各有一个,内容均为 `hello`
    - 真跑走 env 变量取 key(`DEEPSEEK_API_KEY`),未用 `--api-key=` 字面量,header 不含凭据明文

- [x] **文档**:
  - `docs/design/02-module-layout.md`:Shell 模块表加 `harness-shell-docker` 行;依赖图加 `docker` 节点 + `shelldef --> docker` + `docker --> base` 两条边。顺带订正同表两处既有失真:Definition 接口名是 `ShellExecutor`(原文写 `ShellService`,该类不存在)、bash-local 的插件 id 是 `shell-bash-local`(原文写 `bash-local`)
  - `docs/design/05-capability-seam.md`:§5 Definition 代码块补齐 it12 已落地但文档未跟的 `ShellRequest.policy` 与 `ShellResult.outputTruncated/sandboxDenied`;新增 docker Provider 段(七条承重决策 + `--entrypoint sleep` 的实测理由 + 四条已知残余);Consumer 段的渲染改成与 `ShellToolPlugin.format()` 一致;§6 把「容器/microVM 是换掉整条 seam」从预言改写为 it12.5 已验证,并记下后果(docker 直接消费 `SandboxPolicy`、从不调 `confine`)
  - `README.md`:状态段迭代范围 7-12 → 7-12.5;仓库结构 shell 行注明两个互斥 Provider;路线表加 `12.5 ✅` 行
  - `docs/plan/iteration-12.md`:验收后修正表加「(it12.5 契约修订)」行——撤装载期 provider 在场校验,并显式声明 it12 的验收项「受限档无 provider → 装载期 fail loud」随之作废
  - 本文件:勾选 + 证据 + 下表

## 验收后修正

| 提交 | 缺陷 | 修正 |
|---|---|---|
| (实施中) | `HeadlessMain` 的 `--docker` overlay 把 `true`(boolean)传给 `ConfigRowSpec.Replace` 的 `disabled` 组件 → **全 reactor 编译失败**(`不兼容的类型: boolean无法转换为java.lang.String`)。`disabled` 不是布尔旗,是 `ExpressionResolver` 求值的**表达式串** | 改传 `"true"`(裸非空操作数即真)→ resolve 期整行滤除;旁边加注说明为何是串。复验:`mvn -B -ntp package` BUILD SUCCESS |
| (实施中) | `DockerShellExecutor.matchesDialect` 按 `"\n"` 分行,`LocalBashExecutor` 按 `"\\R"` 分行——两个 Provider 实现同一条「行级方言匹配」语义却用两种拼写,读者无法判断差异是否有意义 | 统一为 `"\\R"`;`dialectMatchingIsLineScoped` 补 CRLF 两断言(整行命中真、跨行拼接假),把「行界与平台无关」钉进测试 |
| (本次补齐) | 提案的验收清单要求「DANGER 下全开」与「timeout/**abort**」,但落地的测试只覆盖 WORKSPACE_WRITE/READ_ONLY 与 timeout——两条验收项无测试对应 | 新增 `dangerModeOpensContainerRootAndWorkspace` 与 `abortKillsInContainerProcessGroup`;后者就地实现 `AbortSignal` 小契约(`AbortController` 在 `core/agent-loop`,为取消一条命令拉整条依赖不成比例) |

### 挂账(发现但**不属本迭代**,记录不顺手改)

| 发现 | 判定 | 归属 |
|---|---|---|
| denial 标记以 `exit≠0` 为门:真跑首轮模型自发把命令写成 `… ; echo EXIT_CODE=$?`,wrapper 退出码被自己最后一条语句抹平成 0 → `sandboxDenied` 丢失(EROFS 仍在 stderr,模型读得到,但结构化位没了)。第二轮要求裸命令后标记如期出现 | **非 docker 缺陷**——bash-local 同款 seam 属性,两个 Provider 一致 | seam 层:分类器产出 typed code、按 code 路由,不解析消息文本 |
| `HeadlessMain:217` 硬编码 `Session.newId("headless-1")` → 每次 headless 运行追加进**同一** `log.jsonl`,跨运行事件混账、seq 每轮从头(本次取证须按行号 150–169 切片才能隔离) | **非本迭代引入**,但直接妨碍验收取证与 R1 的「按会话重建」叙事 | examples/headless:运行 id 应带时间戳/随机后缀,或显式 `--session=` |
| `AGENTS.md` 未记 `--docker/--image`;且其「用系统 Maven 3.8+」在本机不成立(无系统 `mvn`,jenv shim 背后是空的,须用 IntelliJ 自带 maven + temurin-25)。`docs/design/07-profile-bundle.md` §9「换 sandbox provider」示例用 `shell-bash-local` + `sandboxMode: landlock`,与 it12(策略逐调用 resolve)和 it12.5(换 Provider 而非配 Provider)双双矛盾 | 两者都**不在本迭代验收清单**(清单列的是 02/05/README + it12 修正表 + 本文件),且 AGENTS.md 是项目指令文件 | 待用户确认后随 it13 一并订正 |
