# 迭代 12 — sandbox + restriction(敢让人跑)

模块:sandbox/sandbox(Definition 去占位)、sandbox/local(Seatbelt Provider)、sandbox/policy(新,策略解析)、shell 三模块(策略通道+wrap)、fs/tool(进程内围栏)、bundle/base(行+重排)、kernel 无改
四确认日期:2026-09-11(同日确认两项取舍:docker Provider 拆 it12.5 单独四确认;完整提权流推迟——denial 标记本迭代只做正确性部分)

## 四确认(已确认 2026-09-11)

- **内容**:
  1. **WP1 Sandbox Definition(sandbox/sandbox)**:文件效果模式词表 `READ_ONLY / WORKSPACE_WRITE / DANGER_FULL_ACCESS`(网络与进程可见性明示在词表外);`SandboxPolicy(mode, workspaceRoot)` **逐调用携带**(不固定在 provider——两个消费者同刻可不同策略);`SandboxProvider.confine(argv, policy)` → `ConfinedArgv(包装 argv, enforcement FULL/PARTIAL, denialSignatures)`——签名是**本后端方言**(Seatbelt=EPERM "Operation not permitted"),消费方不用跨后端并集;`WritableRoots.of(policy)` 规范化单一来源(realpath 去重:darwin 上 /tmp 即 /private/tmp——按拼写授予会匹配不到);fail-closed:`SandboxUnavailableException`(code SANDBOX_UNAVAILABLE,经 executor 转 error result)。05 §6 旧形状(OFF/LANDLOCK/CONTAINER + current())修订回写
  2. **WP2 sandbox-local(Seatbelt 实做,平台链结构,Linux/Windows 设计先行)**:SBPL profile(`(allow default)(deny file-write*)` + /dev/null literal + writable roots subpath);**功能探针**(`sandbox-exec -p <read-only> -- true`,内核拒绝 profile 即不可用,首次 confine 时探测并缓存;显式二进制路径构造器注伪测 fail-closed);**平台链组形**(dsh 对齐:平台→候选链,>1 候选才探针仲裁)——今日 darwin=[seatbelt],linux/win32=空链→受限 confine 抛 SandboxUnavailable,**静默透传被禁止**。landlock/bwrap/windows-acl 不做实现,设计要点落 02/05:landlock(自限制后 exec、规则跨 execve 继承、allow-list 只授不拒、musl 静态链)、windows-acl(**WRITE_RESTRICTED 受限令牌 + per-workspace SID 常设授予 + per-session 随机临时目录/SID,enforcement=PARTIAL 及两洞**——Everyone-可写外部对象仍可写、NTFS 硬链接别名越界;stderr 签名 + exit 127 fail-closed);三者实现挂 it13 CI(Windows/Linux runner)。WritableRoots 平台感知(tmpdir 恒有;`/tmp` 仅非 Windows——盘符相对路径若被创建会成为真实授予)
  3. **WP4 sandbox-policy(策略解析,新模块)**:`SandboxPolicyService.resolve(Session)`——部署默认档(config mode 必配 + workspace);**plan 耦合(it11 挂账兑现)**:`PlanModeService.foldActive(session.events())` 为真且默认档**受限**时压到 READ_ONLY(DANGER 是部署显式弃权,不覆盖——尊重显式选择);requires "plan"+"sandbox provider 在场"(受限档而无 provider → 装载期 fail loud)
  4. **WP3 Restriction 接线**:shell——ShellRequest 增**非空** `SandboxPolicy` 字段(消费端 shell-tool 显式 resolve:既有调用点同步更新,不留隐藏默认);bash-local 对受限策略经 SandboxProvider.confine 包装 argv 再 spawn;ShellResult 增 `sandboxDenied`(exit≠0 且 stderr 命中本后端方言——模型能分辨「被沙箱拒」与「命令失败」)。fs——fs-tool 消费端进程内围栏:READ_ONLY 下 fs_write/fs_edit/fs_delete 拒(error result),围栏与 Seatbelt 共用 WritableRoots(永不漂移);fs-tool requires sandbox-policy
  5. **WP5 测试+组合+文档**:Seatbelt 真实拒绝 e2e(写 /tmp 拒 EPERM+标记;写 workspace 成功)、fail-closed(伪二进制)、plan 耦合三态、fs 围栏、PRODUCTION 档断言 mode≠DANGER;base 行重排(plan/todo 前移,sandbox-local+sandbox-policy 插入 fs 前)——因 sandbox-policy 批内 requires plan;02/05 修订、README、AGENTS
- **目标**:模型的文件副作用有 OS 级边界且 fail-closed;plan 模式从提示词级建议升级为强制只读(受限部署下);模型可辨识沙箱拒绝
- **为什么**:0.1.0「敢让人跑」判据——生产模拟场景要求变异工具在围栏内;it13 发布工程前置;dsh 生产验证的形状(Definition Javadoc 原话:容器/microVM/远程执行是换掉整条 seam,本服务与宿主共享内核与文件系统)直接移植;零依赖(Landlock/Seatbelt 内核自带)是 `java -jar` 用户的兜底线,docker 依赖 daemon 故拆 it12.5
- **不做**:landlock/bwrap/windows-acl 实现(Linux/Windows-only,开发机 macOS 无法实测,it13 CI runner;**Windows 适配已入结构与契约层**——seam Windows-clean、WritableRoots 平台感知、平台链组形、windows-acl 设计落文档);docker/k8s/云 Provider(it12.5 单独四确认——环境级隔离=换 shell Provider,不动本 seam);pwsh provider(bash-local 假设 bash 存在——dsh 的 Windows 档 shell,与 docker provider 同族,it12.5/it13 决策);网络隔离与进程可见性(词表外,挂账);完整提权流(sandbox_permissions+justification+一次性更宽重试——强依赖 ask-user seam 富审批,半吊子形状会返工;denial 标记已做);MCP(it14+);与 dsh 的已知分歧:JH 选择 plan↔sandbox 耦合(dsh 明文解耦且 plan:policy 仅为建议)——理由:JH 的 plan:policy 文本承诺「强制归沙箱层」,耦合使其为真;随 /plan 命令落地 revisit

## 验收(证据 = 实际执行的命令与结果)

- [x] 全 reactor `mvn -B package` 绿(**268 测试** 0 失败 0 错误;新增 14 = sandbox-local 5 + sandbox-policy 4 + WritableRoots 3 + fs 围栏 1 + shell 拒绝标记 1)
- [x] Seatbelt 真实强制 e2e(macOS 实测):READ_ONLY 写 → exit≠0 + EPERM + sandboxDenied;WORKSPACE_WRITE 工作区内 exit 0、用户主目录(授予面外)拒 + 标记;confine 只包装不执行
- [x] fail-closed:伪 seatbelt 二进制 → SandboxUnavailableException("refusing to run the command unconfined");透传档进 confine → IllegalArgumentException;受限档无 provider → 装载期 fail loud
- [x] plan 耦合三态:无事件=默认档;plan/mode(true)=READ_ONLY;true+默认 DANGER=仍 DANGER(显式弃权)
- [x] fs 围栏:plan 会话 fs_write 拒("denied by sandbox…read-only")、fs_read 放行、零变异落盘;WritableRoots 规范化(realpath)单测
- [x] PRODUCTION 断言 mode≠DANGER 在案(verifyProductionRejectsAutoApproval 等全绿);`--verify` 通过(21 插件行)
- [x] 真跑(DeepSeek):模型并行调 bash——区内写 exit 0(临时工作区 inside.txt 在);越界 `echo > /Users/blocked-test.txt` → exit 1 + `[sandbox: a file effect was denied]` 标记 + EPERM,文件未创建;模型自报两结局
- [x] 文档:05 §6 重写(dsh 形状)、02 模块表三行、README 12✅、AGENTS、本文件

## 验收后修正(如有)

| 提交 | 缺陷 | 修正 |
|---|---|---|
| (实施中) | BashLocalPlugin 显式构造器路径在构造期固化 executor,漏 resolve SandboxProvider → 显式组合下受限请求误报"no sandbox provider" | 重构为 apply 时统一装订(选项解析 + provider 解析各一处,两路径等价) |
| (实施中) | headless overlay 只钉 fs-local/shell-tool 的 workspace,漏 sandbox-policy → Seatbelt 授予面(examples/headless)比围栏(临时工作区)宽 | overlay 增 sandbox-policy 行,三处钉到同一临时工作区;真跑复验越界 bash 写被拒 |
| (真跑复验) | 首次真跑"demo.txt 消失"疑云 | 非缺陷:headless 本就跑在 `createTempDirectory("jh-headless")` 临时工作区,文件落在临时区;模型的绝对路径被 fs-local root 政策正确拒绝——双围栏行为均正确 |
| (it12.5 契约修订) | sandbox-policy 的装载期校验「受限档必须有 `SandboxProvider` 在场,否则 fail loud」把 `confine` 当成唯一强制点。环境级隔离(docker 容器)的强制点是**容器边界(挂载面)**,它刻意不实现 `SandboxProvider`——该校验会误拒合法组合 | it12.5 **撤销该装载期校验**,与 dsh 对齐(dsh 本就是运行期 fail-closed)。**本文件验收项「受限档无 provider → 装载期 fail loud」随之作废**;强制故事改为:bash-local 的逐调用 fail-closed belt(it12 已实现+已测,保留不动)+ docker Provider 直接消费 `SandboxPolicy`。PRODUCTION 探针(档位≠DANGER)不受影响。详见 [iteration-12.5.md](iteration-12.5.md) 风险点 3 |