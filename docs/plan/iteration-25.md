# 迭代 25 — Windows 与 0.2 发布验收（状态：四确认与裁决已落盘（2026-09-28）；S-0 探针首跑已收口（真 VM 取证，形状订正：WRITE_RESTRICTED → 低完整性）；**S-a（windows-acl 后端与 FFM 绑定）停点获条件放行（2026-09-28）——放行条件项（WindowsAclTest 两处断言面缺陷，主源码零改动）已修正并经突变 M6/M7 红→还原复绿（聚焦四类 70/0/0/15 + AppBootTest 21/0/0/0），三处顺手带上（05 §6 退出码去 11 / `PROBE_TIMEOUT_SECONDS` 全局影响入增量表 / AGENTS.md FFM 豁免条款）已落，收口单提交已出**——真 VM 全链取证（探针七腿 + 真跑六题 + 两洞实测）+ 突变红/绿对 + 全量 package 573/0/0/17 绿 + 被测类一致性复校（a5-classes 同构不受本次修正影响）；**D4 维持「待 S-c 首轮 CI 事实再裁、不带预判」——选项 A/B 随 packet 呈报**；**S-b（shell 平台化与契约迁移面）停点获放行（2026-09-29）——三处放行条件已落（design README 漏迁移订正 / AGENTS.md 四模块计数 / 分区归因改写），收口单提交已出**：改名泛化（`shell/bash-local` → `shell/local`：包/插件 id/工具名/坐标）+ 平台分派（POSIX=bash / Windows=pwsh，缺席 fail-loud）+ 测试四类分区（+ 第五类随动）+ 迁移涟漪（consumer-sample / verify-consumer / embedding / 文档面）；**真 VM 全链 15 腿全过**（载荷三向哈希一致 + treehash 66/66 与 VM 同构 + 分区集 JUnit 12 找到 / 1 跳 / 11 过）；**真 Windows 首跑暴露产品缺陷 1 处已修**（`System.getenv()` Map 视图精确键名 vs Windows 惯名 `Path` ⇒ pwsh 永不入候选；主源码唯一改动 = `ShellPlatform.pathEntries(Map)` 大小写不敏感 + 回归用例，突变 M-Sb6 红→复绿）；突变 M-Sb1–M-Sb6 红/绿对；消费方双腿绿（candidate 失败项 0 / release 旧坐标覆写失败项 0）；全量 package **586/0/0/22** 绿；**S-c（Windows 交付）已收口（2026-09-29）**——locale 修复（入口 stdout/err UTF-8 wrap + 助手 `-Dstderr.encoding=UTF-8` + 消费侧方言按 `native.encoding` 候选解码）+ 两跳引号产品缺陷修复（命令 `-EncodedCommand` / 助手 argv `--argv-b64` 单参载体）+ 真 VM 归档构建（`windows-aarch64` zip/tar.gz，sha `49a0c578…`/`98ea576c…`）与解压冒烟 A/A2/B/C/D/D2/E/F/G/H 腿 + CI windows job 落地（8 步：前置探针 + surefire 点名断言 + jlink/归档双冒烟 + 归档上传）+ 全链复绿（真 VM 83 类 586/0/0/54；本机 596/0/0/23）——见 `S-c-windows-delivery.txt`；**CI 首跑事实（含 ubuntu job 的 D4 landlock 测量项）待 push 授权**；S-d 未启；未推送）

模块：`sandbox/local`（win32 链 + windows-acl 后端 + FFM Win32 绑定/助手）· `shell/bash-local` → `shell/local`（平台分派 + 工具名/坐标迁移）· `examples/headless`（入口 UTF-8 wrap）· `dist/jh`（windows 归档真构建）· `.github/workflows/ci.yml`（windows job）· 测试平台分区四类（增强 1）· `integration/consumer-sample` + `integration/verify-consumer.sh` + `docs/embedding.md`（3a 改名涟漪）· `bundle/base`（bundle.yml 行）· `docs/design/{02,05,07,10,12,README}` · `README{,.zh-CN}.md` · `docs/release.md`

路线来源：路线 it25 行「**Windows 与 0.2 发布验收**：pwsh、本机隔离、进程树清理、路径/编码适配及发行包」——验收「真 Windows 环境完成安装、任务、拒批、取消和恢复；已支持平台回归；不是只装 pwsh 即算支持；发布工件与稳定面核对齐全」（`docs/design/README.md:147`）。平台承诺（`docs/design/README.md:131`）：「Windows 本机隔离 + pwsh、Linux Landlock fallback 保留在 0.2.0，不静默顺延」。

## 四确认

- **内容**（四件）：
  1. **win32 本机隔离（windows-acl 后端）**：`PLATFORM_CHAINS` `win32=[]` → `[windows-acl]`；按 `05 §6:474-478` 既定设计——WRITE_RESTRICTED 受限令牌 + per-workspace SID 常设授予 + per-session 随机临时目录/SID；`enforcement=PARTIAL` + 两洞（Everyone-可写外部对象仍可写、NTFS 硬链接别名越界）如实登记，stderr 签名 + exit 127 fail-closed。探针强度对齐 it24/bwrap：真建 + 真限 + 真跑 + 验真拒写（含限前正对照，防 DAC 误绿）。实现 = FFM 直呼 Win32（`CreateRestrictedToken` / `AllocateAndInitializeSid` / DACL 修改 / `CreateProcessAsUser`），无原生二进制、无 C 工具链。**拓扑与 landlock 不同**（增强 2）：助手（JVM）自身不受限，受限的是它创建的子进程；不变量 = 「子进程唯一生成路径是带受限令牌的 `CreateProcessAsUser`；令牌构造任一步失败即无子进程」；kill-tree 是真父子链（`descendants()` 可达）。**（订正 2026-09-28：该机制经 S-0 真 Windows 实探证不可用——0xC0000142；形状改为低完整性（Low IL），两 SID 机制退役、两洞重述，详见下「S-0 首跑实探结论」节；**S-a 已按订正形状落地并经真 VM 全链取证，05 §6 与同步面已改写**——见「锚点」与「设计偏离」表）**
  2. **pwsh 执行面**（D3 3a）：shell 提供者平台化——`shell/bash-local` → `shell/local`：包 `shell.local`、插件 id `shell-local`、工具名 `bash` → `shell`；POSIX=bash（现状语义）/ Windows=pwsh（`-NoProfile -NonInteractive`）；拒绝方言、退出码与引号差异按平台登记；进程树清理在 Windows 上真验（`descendants()` 语义是否足 / Job Objects / `taskkill /T /F`，实探后定形）。**迁移涟漪**：0.1.0 已发布坐标 `harness-shell-bash-local` 被替换（0.2.0 迁移注记）；consumer-sample 16 依赖闭包、`docs/embedding.md`、`integration/verify-consumer.sh` 随动（candidate 腿用 0.2.0-SNAPSHOT 新坐标；release 腿钉 0.1.0 不动）。
  3. **Windows 交付与 CI**：windows 归档真构建 + 解压冒烟（`jh.bat --help`/`--verify` + 一条受限命令真拒写）；CI 新增 windows job（**平台分区完成后**才开——增强 1）；路径/编码适配——locale 修复随本段早落（形状已裁：`HeadlessMain` 入口 UTF-8 wrap + 助手 JVM `-Dstderr.encoding` 旗标）、盘符与 `%TEMP%`、CRLF。
  4. **0.2 发布工程（S-d，可分离）**：Release notes（it17–it25 破坏性变更与迁移路径汇总——`12` 已逐条在案）、稳定面全量核对（`12 §2` 导出面扫描）、`docs/release.md` 与 README×2 更新、**tag→GitHub Release 附件自动化**（it24 点名回收；本迭代出工作流定义，真 tag/Release/Publish 执行 = 用户侧）。

- **目标**：真 Windows 上完成「安装 → 任务 → 拒批 → 取消 → 恢复」全链；已支持平台（macos/linux）回归不破；不是只装 pwsh 即算支持——受限档由 windows-acl 真强制（PARTIAL 两洞如实，不冒充 FULL）、不满足约束 fail-closed；0.2.0 发布工件与稳定面核对齐全、迁移路径成文。

- **验收问题**（每轮一问）：*Windows 用户在未装任何额外工具的主机上让 Agent 受控地改工作区文件*——今天两条路都断（受限档 win32 空链 fail-closed；命令执行无 pwsh 通道）；it25 后：归档解压即跑、命令走 pwsh、受限档真强制（PARTIAL 如实）、拒批/取消/恢复在真 Windows 可验。

- **为什么**（现状证据）：
  - win32 链空（`SandboxLocalPlugin.java:48-51`），空链文案已点名「windows-acl planned — 0.2.0」（`:130-133`）⇒ Windows 受限档首条命令必 fail-closed（**S-a 后已接上 `[windows-acl]`**，空链形态不复存在；文案已随动）。
  - 平台承诺落盘（`docs/design/README.md:131`）；路线 it25 行（`:147`）是本兑现窗口。
  - `05 §6:474-478` 设计已就位（受限令牌/SID/双洞/PARTIAL/exit 127 逐字在案），实现挂 0.2.0（**S-a 已按订正形状改写并落地**——低完整性 + 逐对象打标 + 两洞实测）。
  - shell 只有 bash 一路：`LocalBashExecutor.java:53` 硬编码 `bash -c`；工具名 `bash`（`ShellToolPlugin.java:92`）；`LocalBashExecutorTest` 6 条无平台门、`ShellToolEndToEndTest` 前两用例无门（第三用例有 MAC 门）⇒ Windows 上必红。
  - `dist/jh`：`archive-windows` profile 已在（`dist/jh/pom.xml:139-143`）但从未真构建；CI 仅 ubuntu/macos 两 job。
  - it24 两项挂账归位：locale 修复 → 本迭代 S-c（形状已裁）；`/dev/null` allow-list 推论 → S-a 落地测量项（`SandboxLocalTest#landlockDevNullWriteIsMeasuredForAdjudication`）——**本机无 landlock 宿主 ⇒ 事实唯 CI（S-c 首轮）可出**，选项 A/B 随 S-a packet 呈报（见「设计偏离」表）。
  - it24 点名推迟项：tag→GitHub Release 附件自动化 → 本迭代 S-d。

- **不做**：WSL/git-bash 通道（Windows 承诺的解释器是 pwsh）；Windows 容器（shell-docker 维持 POSIX 主机面）；windows-acl 两洞的消除（属 AppContainer/低完整性等更大改造，另行排期）；MSI/安装器/服务化（解压即用形态不变）；真 tag/Release 上传/Central Publish 的执行（用户侧，it16 先例）；网络与进程可见性、DANGER 档、提权流、资源限额照旧；0.1.x 已发布面语义回改（0.2.0 破坏面走迁移注记）；非 windows 平台新特性。

## 裁决记录（2026-09-28）

用户裁决：**草案成立；四项裁决全部可定，另附三处事实订正与七条增强。**

| 裁决 | 内容 |
|---|---|
| D1 | **验收主体 = keyless CI windows job（无需 secret）**：五个验收面全部可 keyless——任务 = 假服务端 SSE 驱动真 CLI 子进程 + 真 pwsh 工具调用（`HeadlessFakeServerE2ETest` 先例）；拒批 = EOF/审批超时（先例在案）；恢复 = `HeadlessResumeTest` 先例；取消 = `Signal.raise("INT")` 机制（**Windows 无 POSIX SIGINT，行为未知数 ⇒ 首步实探**）。真模型 Windows 跑 = 可选补充证据（it21/it22 real-run 先例），场所 = 本地 Windows VM（Win11 25H2 ARM64 ISO + UTM 已下载）|
| D2 | **一轮四面**；S-d 天然可分离——S-c 出口带证据决定是否甩成 it25.1（仍属 0.2.0），零损失 |
| D3 | **3a 改名泛化**（`shell/local` 平台分派；sandbox-local 的 `PLATFORM_CHAINS` 即先例；3b 双行并行会逼出「同 OS 双 provider 谁生效」的组合语义，违背单写者纹理）；涟漪见内容 2 |
| D4 | **`/dev/null` 顺手实测出选项**（runner 真 landlock 宿主 ABI v7，一条 e2e 出事实再裁，不带预判）|

**三处事实订正**（已并入正文）：

1. `ShellToolEndToEndTest` 并非全无门——第三用例有 MAC 门，前两确无（Windows 必红）；
2. it24 locale 修复未执行（台账口径「未改码、待裁」），it25 路线行「编码适配」自然归位；修复形状已裁（入口 wrap + helper `-Dstderr.encoding`），不再悬置；
3. `LocalFsTest` 大概率在 Windows 绿（围栏校验先于磁盘访问）——不动。

**七条增强**（并入锚点与实探清单）：

1. **测试平台分区 = CI windows job 的前置**：`LocalBashExecutorTest`（6 条）、`ShellToolEndToEndTest` 前两用例、`HeadlessCrashResumeE2ETest`（真 bash 子进程 + sleep 后代探测）、`DockerShellTest`（`@BeforeAll` 的 `sh -c` 在 daemonUp 门之前爆，Windows 上整类打成错误）——四类不分区，windows job「全量 package」到达即红。**顺序依赖显式化：S-b 分区完成，S-c 才开 windows job。**
2. **windows-acl 助手拓扑与 landlock 不同**（见内容 1）；landlock（自限制后 exec）/ windows-acl（helper 受限生子）/ docker（容器内 setsid）三种拓扑在 05 §6 各自写明，不混说。
3. **FFM 纪律从出生就上**（it24 血泪正向应用）：每函数一个类型化包装 + 绑定层测试；Windows 侧无 raw syscall 蹦床——直接绑 advapi32/kernel32 符号，类型来自真原型（it24 评审「libc 符号优先」的理想形态在 Windows 免费成立）；代价 = 符号在 macOS 不存在 ⇒ 绑定测试只在 Windows 跑。
4. **首步实探清单（S-0）**：`Signal.raise("INT")` 在 Windows 的行为（取消验收地基）· pwsh 存在性与版本 · `jh.bat` 真由 jlink 产出且归档解压可跑 · **FFM API 全谱**（`CreateRestrictedToken` + `AllocateAndInitializeSid` + DACL/ACL 修改 + `CreateProcessAsUser`）——任一符号/结构布局不可用都改变实现形状。
5. **locale 修复随 S-c 早落**（形状已裁：入口 wrap + helper 旗标），不第三次顺延。
6. **consumer-sample / embedding / verify-consumer 随 3a 改名联动**（D3）。
7. **`Ready.detail` 按 it24 契约携带 PARTIAL + 两洞**——不冒充 FULL，「PARTIAL 如实」由结构保证。

## S-0 首跑实探结论（2026-09-28，UTM Win11 25H2 ARM64 VM 真跑）

**场所与配方**：UTM VM（WIN-088T9S8V0LT / 用户 j'h / 本机管理员 / EnableLUA=0 / JDK 25.0.4.1，JDK 由载荷 zip 就地展开；本机 `utmctl` = `/Applications/UTM.app/Contents/MacOS/utmctl`，VM uuid `91EA42FC-F894-4E2A-AF34-EA6393127351`）；投递 = `utmctl file push`，执行 = schtasks（`/ru "j'h" /it`）在交互会话内跑 cmd（重定向落盘），回读 = `utmctl file pull`——exec 的 stdout 有陈旧输出先例在案，一律以文件回读为准；控制台 GBK，入库文本已转 UTF-8。

**首跑事实**（探针套件在同机连续真跑 run1–run6：逐次暴露并订正五处套件缺陷后，run6 全腿走通）：
- `Signal.raise("INT")` handler 真触发 ⇒ 取消验收的机制地基成立（Windows 无 POSIX SIGINT 的未知数消除）；
- **pwsh 不存在**（仅 Windows PowerShell 5.1.26100.7920）⇒ Windows shell 候选链与安装文档须按「找 pwsh、缺失即点名 fail loud / 明确降级」设计，不能默认 pwsh 在场；
- 编码：native/stdout/stderr=GBK、`file.encoding=UTF-8`、中文与 em-dash 样本正常 ⇒ locale 修复形状维持既定（入口 wrap + 助手 JVM 旗标）；
- FFM 全谱符号在场（advapi32 十一 + kernel32 四；`LocalFree` 归 kernel32——advapi32 不导出）；捕获状态段 12 字节；
- 探针落点 **`failures=1`**：deny-write / grant-write 两条受限腿的子进程均 0xC0000142——由此触发形状追查（下节）。

**形状订正（S-0 的正式产出；逐轮原始输出与结论 = `S-0-windows-acl-shape.txt` + 诊断源 `S-0-windows-acl-shape.java`）**：
- 既定设计（WRITE_RESTRICTED + per-workspace SID 授予 + per-session 随机 SID）**物理不可用**：CREATE_NO_WINDOW 下受限子进程一律 0xC0000142（令牌构造与 ACL 授权全 OK 也死；给 dir/HKCU/WinSta0/Default 乃至自建 window station + desktop 授权均不救）；唯一能过启动的受限 SID 是 Administrators，而其一旦入列，写限制**形同虚设**（未授权目录照写——P4 直证）⇒ 该形状无安全价值、整体退役；连带 **per-workspace SID 常设授予、per-session 随机 SID 两机制退役**（令牌构造不再需要 AllocateAndInitializeSid）；
- **改取低完整性（Low IL）形状**：`CreateRestrictedToken(DISABLE_MAX_PRIVILEGE, 0 受限 SID)` + `SetTokenInformation(TokenIntegrityLevel, {S-1-16-4096, SE_GROUP_INTEGRITY})`——CREATE_NO_WINDOW 下子进程正常启动；写 Low 标签目录**成功**、写中标签目录（含 DACL 已授权的）**被拒**（exit=1 且进程不死亡）——正/负对照全数符合预期；
- **打标纯 FFM 可达（免 icacls）**：`ConvertStringSecurityDescriptorToSecurityDescriptorW("S:(ML;OICI;NW;;;LW)")` → `SetNamedSecurityInfoW(LABEL_SECURITY_INFORMATION=0x10)`（管理员下 0x4 SACL 位亦成功；采用 0x10）；
- **新增义务与足迹**：标签不向已存在子对象回溯传播 ⇒ 会话可写区域需**逐对象打标**（目录 + 已存在文件，新对象靠 (OI)(CI) 继承）；打标对宿主对象是持久元数据（会话结束/崩溃的还原义务、大树递归成本 = S-a 设计决策项）；
- **两洞重述（Ready.detail 携新版，替代旧版）**：洞 1 = 「可写面 = 主机上一切 Low 标签对象」（不再限于工作区树；MAC 不理会 DACL）；洞 2（硬链接别名越界）**归待实测**（推论：硬链接与目标同对象同标签 ⇒ 不构成越界）；
- **S-a 实测订正（2026-09-28，真 VM 两腿直证）**：洞 1 **维持且成立**（foreign 目录由 icacls 标 Low 后，任一会话可写其中对象——「可写面 = 主机一切 Low 对象」不止工作区）；洞 2 的上述推论**被推翻**——递归打标遍历**跟随硬链接**（硬链接无 reparse 属性，跳重解析点的守卫拦不住），工作区内的硬链接把 Low 标签打到**工作区外**的目标对象上、该对象永久进入可写面（leg10：树内 `mklink /h` 别名，树外目标 `hl-target.txt` 被标 Low 且经别名写入成功）。两洞以实测形状进 05 §6 与 `Ready.detail`；
- 拓扑不变量维持（增强 2）：助手（JVM）不受限、子进程唯一生成路径 = 带受限令牌的 `CreateProcessAsUser`、令牌构造任一步失败即无子进程。

**订正清单（S-a 绑定层照做；逐条含原始输出见证据档第四节）**：advapi32 无 `LocalFree`（归 kernel32）· Windows 捕获状态段 12 字节 · `AllocateAndInitializeSid` 8 个 sub-authority · 语句上下文 `invokeExact` 编成 (…)void 描述符（返回值必须承接）· 受限 SID 的 `Attributes` 必须 0（IL 标签则必须 SE_GROUP_INTEGRITY）· `TOKEN_GROUPS` 数组偏移 8 · `OpenWindowStationW/OpenDesktopW` 返回句柄 · `Get/SetSecurityInfo` 参数序 · 打标 SD 为自相对布局（SACL 指针取偏移 12 的 DWORD）。

**未实测边界（诚实登记）**：硬链接归属（上）· 子进程对 Medium 对象连标签也写不动（推论，未实测）· 子进程 TEMP/TMP 宜指向 Low 目录（推论性提示）· 标签还原义务与递归打标成本（设计决策项）· `CreateProcessWithTokenW` 备选腿（真启动 pid=788 但退出码不可判定，且不在采纳形状内，未追查）。**S-a 结清情况（2026-09-28）**：硬链接归属已实测（见上订正）· TEMP/TMP 指向已实现且实测（会话私有 Low 临时目录，leg5 直证）· 打标成本已测（≈0.23 ms/文件）· 「标签还原」裁定为 **0.2 不做还原**（持久足迹登记进 05 §6 与结论行）；仍维持未测：子进程对 Medium 对象连标签也写不动（推论）、`CreateProcessWithTokenW` 备选腿。

**未覆盖项（S-0 余项）**：探针套件不含 `jh.bat` 真由 jlink 产出且归档解压可跑一项（VM 内无构建产物）——归 S-c 归档真构建 + 解压冒烟，本 VM 可作场地。

## 设计增量（ADDED / MODIFIED / REMOVED）

- ADDED：
  - `sandbox/local`：windows-acl 后端（FFM advapi32/kernel32 类型化绑定 + 负向/正向探针 + 受限令牌建子进程）
  - `sandbox/local`：低完整性打标面（`ConvertStringSecurityDescriptorToSecurityDescriptorW` + `SetNamedSecurityInfoW(LABEL_SECURITY_INFORMATION)`；会话可写区逐对象打标 + 还原）——S-0 订正后的 win32 形状（见上「S-0 首跑实探结论」）
  - `shell/local`（由 `shell/bash-local` 改名泛化）：平台分派（POSIX=bash / Windows=pwsh）+ 工具名 `shell`；`ShellPlatform`（宿主探测 + argv 分派 + PATH 扫描，含大小写不敏感 `pathEntries(Map)` 测试缝）
  - `shell/shell`：公共类型 `ShellUnavailableException`（解释器缺席的 fail-loud 契约面；`LocalShellExecutor` 抛出、工具面透传文案）
  - CI windows job（`.github/workflows/ci.yml`）+ 四类测试平台分区
  - tag→GitHub Release 附件工作流（tag 触发；真执行用户侧）
  - `examples/headless`：入口 UTF-8 wrap（locale 修复）
- MODIFIED：
  - `PLATFORM_CHAINS`：`win32` `[]` → `[windows-acl]`
  - `sandbox/local` · 功能探针外层上限 `PROBE_TIMEOUT_SECONDS`：5 → 60（**全局影响**：该护栏对**所有**平台链生效——探针挂起时 fail-closed 的等待上界从 ≤5s 变 ≤60s，seatbelt/bwrap/landlock 腿同受此改；腿级裁决不受影响，它是外层防僵护栏，windows-acl 各腿另有腿内自带上限——最坏 ≈ 4×5s 腿 + 8s 嵌套会话腿）
  - 坐标迁移（0.2.0 迁移注记）：`harness-shell-bash-local` → `harness-shell-local`；插件 id `shell-bash-local` → `shell-local`；包 `shell.bash.local` → `shell.local`；工具名 `bash` → `shell`——同步面：`pom.xml:201`、`bundle/base/pom.xml:78`、`shell/tool/pom.xml:35`、`shell/bash-local/pom.xml:13-14`、`integration/consumer-sample/pom.xml:97`、`bundle.yml:52-53`、`dist/jh` 模块表、`12 §2/:40/:108`、`05:238/:318`、`07:58/:295`、`10:376`、`02:70`、`integration/verify-consumer.sh`、`docs/embedding.md`
  - `integration/consumer-sample`：shell provider 坐标收敛为**单点属性** `<harness.shell.artifactId>`（默认新名）；`integration/verify-consumer.sh` 的 release 腿显式覆写回旧名（`-Dharness.shell.artifactId=harness-shell-bash-local`，两腿同源源码、只有坐标单点不同）——M-Sb5 突变证明该单点承重
  - `docs/embedding.md`：依赖清单与工具面口径随改名订正（工具面逐名枚举：0.1.0 = 8 / 0.2.0 = 10）+ 新增迁移对照行（坐标 / plugin id / tool 名）
  - `Ready.detail`：windows-acl 行携带 PARTIAL + 两洞（结构保证，不冒充 FULL）
  - 测试平台分区四类（增强 1）
  - 文档同步：05 §6（三拓扑分别写明 + win32 链实况）、02 模块表、07/bundle 行、12 稳定面（迁移面）、README×2 平台段与归档指引、`docs/design/README.md`（it25 状态随收口）、12 §6 输出编码固定 UTF-8 条（S-c）、AGENTS.md 取回文本按来源选编码（S-c）——`docs/release.md`（windows 归档、附件自动化）归 S-d
  - S-c 产品面修正：`ShellPlatform` `-EncodedCommand` 载体 + 助手包装协议 `--argv-b64` 单参载体（两跳引号保真，旧形态 fail loud）；`LocalShellExecutor` 方言匹配改按 stderr 原始字节 × 候选解码集（UTF-8 + `native.encoding`）；`LocalFs` 结果路径归一 `/`；`SpineMain`/工具 e2e 侧 JSON 路径转义；根 POM spotless `<lineEndings>UNIX</lineEndings>`（详见「修正」表）
- REMOVED：无（改名属 MODIFIED；0.1.0 坐标保留在已发布历史）

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `sandbox/local` · `PLATFORM_CHAINS`(:48-51) + 新增 WindowsAcl 绑定/助手 | win32 链 + 受限令牌生子（拓扑见内容 1）+ 探针 | ✓（S-a，2026-09-28：新文件 `HelperLaunch`/`WindowsAcl`/`WindowsAclExecMain` + 测试 `HelperLaunchTest`/`WindowsAclTest`） |
| `sandbox/local` · `SandboxLocalTest`（WINDOWS 腿 + `/dev/null` 实测项，D4） | 平台 e2e + it24 挂账实测 | ✓（S-a，2026-09-28：windows-acl Ready/PARTIAL 腿 + D4 测量项落地；**事实待 S-c 首轮 CI**——本机无 landlock 宿主） |
| `shell/bash-local` → `shell/local`（pom / 包 / 模块名 / plugin id / 工具名 / 方言） | 平台分派 + 迁移涟漪 | ✓（S-b，2026-09-28：`ShellPlatform` 平台分派（POSIX=bash / Windows=pwsh，缺席 `ShellUnavailableException` fail-loud）+ 新公共类型 `ShellUnavailableException`（shell/shell）+ 方言登记（退出码映射/引号/`$env:`/重定向 CRLF）；**真 VM 15 腿全过** + 进程树两形状实测（`descendants()` 足，Job Object 不需要）；主源码唯一改动 = 首跑暴露的 PATH 键名大小写缺陷修正 + 回归用例（见「修正」表）） |
| 四类测试平台分区（增强 1） | POSIX 门 | ✓（S-b，2026-09-28：**分区净状态 = 四类全落**（+ 第五类随动）。**S-b 新加的门**：`ShellToolEndToEndTest` 类级 POSIX 门（夹具为 POSIX 文形）、`shell/local` 测试面双类分区（`LocalShellExecutorTest` POSIX / `LocalShellExecutorWindowsTest` Windows）、`HostileInstructionsTest` 建链腿方法级 POSIX 门（第五类，实探新发现）。**既有设施、非 S-b 新加**（只做改名随动 / 去 sh-xargs）：`HeadlessCrashResumeE2ETest#assumePosix`（it19，d3bab74）与 `DockerShellTest` 方法级门（it12.5，4371da2）；`DockerShellTest#cleanOrphanedContainers` 去 sh/xargs 改 Java 侧拆分（Windows 宿主无 sh）。VM 分区集 JUnit 12/1 跳/11 过、本机聚焦 18/0/0/5、全量 586/0/0/22） |
| `examples/headless` · `HeadlessMain` 入口 + 助手 JVM 旗标 | UTF-8 wrap | ✓（S-c，2026-09-29：入口 wrap（stdout/stderr 按 UTF-8）+ 助手 JVM `-Dstderr.encoding=UTF-8`（HelperLaunch 统一注入）+ 消费侧 stderr 按宿主编码解码；VM 取证 G1 GBK 负对照 / G2 UTF-8 正样（`中文—em-dash 样本` 纯 UTF-8 可解）+ `HeadlessStdStreamsTest` 真跑 2/0/0/0） |
| `dist/jh` · `archive-windows`（it16-S3 既有 profile）+ assembly 冒烟 | windows 归档真构建（实况：profile 与 assembly 无需改动——本次交付 = 真 Windows 构建 + 解压冒烟） | ✓（S-c，2026-09-29：真 VM 构建出 `javanatic-harness-0.2.0-SNAPSHOT-windows-aarch64.{zip,tar.gz}`（sha `49a0c578…` / `98ea576c…`）+ 解压冒烟 A-help/A2-sessions/B-verify/C-probe 四腿全 exit=0（沙箱行点名 windows-acl PARTIAL）） |
| `.github/workflows/ci.yml` | + windows job（分区完成后） | ✓（S-c，2026-09-29 **job 已落**（8 步：build+test → windows-acl 前置探针（exit 0 + `windows-acl: ready` + `PARTIAL` 三面断言）→ surefire「跑了且没跳过」断言（SandboxLocalTest 2 + WindowsAclTest 7 + LocalShellExecutorWindowsTest 5 条点名；已对真实报告核过 missing=[]）→ jlink/归档冒烟（同一 windows-acl 落点行断言）→ upload-artifact `windows-amd64`）；ubuntu job 的 landlock 断言清单随 D4 加入 `landlockDevNullWriteIsMeasuredForAdjudication`；**首跑事实待 push 授权**） |
| `integration/consumer-sample` + `verify-consumer.sh` + `docs/embedding.md` | 3a 改名随动 | ✓（S-b，2026-09-28：坐标单点属性 `<harness.shell.artifactId>`（默认新名）+ release 腿显式覆写旧名（两腿同源）；embedding 依赖清单与工具面口径订正 + 0.1.0 注记；双腿真跑：candidate 失败项 0 / release 失败项 0） |
| 文档同步面（05/02/07/10/12/README×2/release.md/design README/bundle.yml） | 迁移注记与平台段 | ✓（S-a 主体（05 §6 改写等）+ S-b 迁移注记随动：各面 shell-local/shell 新名与旧名对照；it25 状态行随 S-b packet 收口） |

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行，全机械迭代写「无」）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| S-a：**windows-acl 后端与 FFM 绑定**（安全不变量承载：受限令牌构造、子进程唯一生成路径、探针正负对照、PARTIAL 两洞登记；**机制形状已订正为低完整性——见「S-0 首跑实探结论」节，含递归打标与还原决项**） | `sandbox/local` 新绑定 + 探针 + `PLATFORM_CHAINS` | **停点已达 → 条件放行（2026-09-28；放行条件项两处断言面缺陷已修正、突变 M6/M7 红→还原复绿、三处顺手带上已落——见「修正」表，主源码零改动）**：FFM 绑定（advapi32/kernel32 类型化，S-0 订正 12 条照做；绑定层测试仅 Windows 跑）+ `HelperLaunch` 共享助手启动面（landlock 腿随动）+ `WindowsAclExecMain` 协议 + provider 接线（win32 链 / `windowsAclWrap` / PARTIAL / 拒绝方言双形态）；**探针七腿于真 VM 全过**（真建真限真跑 + 正对照 + 真拒写 + 剥特权嵌套会话；exit=0，结论行 = Ready.detail 来源）；真跑六题（read-only 拒写 / stdio 直通 / workspace 新写与既有追加 / 禁写越界 / TEMP 改道 / `&` 与内引号 / 退出码 7）+ 打标成本实测（≈0.23 ms/文件）；**两洞真 VM 实测**——洞 1 成立（可写面 = 主机一切 Low 对象，foreign 目录 leg9 直证），洞 2 **成立且推翻 S-0 推论**（递归打标跟随硬链接，链入树外部目标被标 Low，leg10 直证）；突变 M1–M3（本机生产面 3 条）+ M5（VM TEMP/TMP 占位化）+ 容器门移除对，全红 → 还原复绿；回归：聚焦四类 69/0/0/15 + `AppBootTest` 21/0/0/0 + 全量 package **573/0/0/17**（30 个含测试模块，跳过皆平台分区）+ 被测类一致性复校（重编后仍 27 类同构）；**D4：测量项落地但本机无 landlock 宿主 ⇒ 事实唯 CI 可出（待 S-c 首轮回填），选项 A/B 随 packet 呈报** |
| S-b：**shell 平台化与契约迁移面**（跨模块：工具名 / 插件 id / 坐标 / 分区 / 文档同步面） | `shell/local` + 分区 + 迁移面 | **停点获放行（2026-09-29）——三处放行条件已落（design README 漏迁移 / AGENTS.md 计数 / 分区归因改写），收口单提交已出**：改名泛化全落（包 `io.javanatic.harness.shell.local` / 插件 id `shell-local` / 工具名 `shell` / artifactId `harness-shell-local`；`ShellUnavailableException` 新公共类型；工具面文案与提示词涟漪）；平台分派（POSIX=bash / Windows=pwsh `-NoProfile -NonInteractive -Command`，pwsh 缺席 fail-loud——无 5.1 静默兜底）；**真 VM 全链 15 腿全过**（leg0a 缺席拒/leg0b 就位 READY、真执行与方言、进程树两种孙进程形状 40/41ms 消失、fail-closed 腰带、编码 leg9/leg9b、迁移 ids、分区集 JUnit）；**产品缺陷 1 处经真 Windows 首跑暴露并修复**（PATH 键名大小写，见「修正」表；主源码唯一改动）；突变 M-Sb1–M-Sb6 红/绿对；消费方双腿（candidate/release）失败项均 0；全量 package 586/0/0/22 绿；载荷三向哈希一致 + treehash 66/66 与 VM 同构；证据档 `S-b-shell-platform.txt` |
| （S-c / S-d 不设停点：机械为主；S-d 可分离见 D2） | | |

## 取证（packet 前置：命令 / 关键输出行 / EXIT 回显落盘 docs/plan/evidence/iteration-25/）

| 文件 | 覆盖（停点/验收项） |
|---|---|
| `S-0-windows-probe.java` + `S-0-windows-probe.txt` | 首步实探（增强 4）：Signal INT / pwsh / encoding / FFM API 全谱（符号 + 令牌 + 生子 + 正负写腿）；**真 Windows 首跑已落盘（2026-09-28，UTM VM）**——探针六次真跑（前五次各自暴露一处套件缺陷并订正，见证据档第三节）；run6 输出 GBK→UTF-8 原样落盘：Signal INT handler 触发、pwsh 缺失（PS 5.1）、FFM 符号全在场、`failures=1`（受限写腿 0xC0000142 → 触发形状追查） |
| `S-0-windows-acl-shape.java` + `S-0-windows-acl-shape.txt` | S-0 形状追查（轮次 8–12 原始输出逐字 + 结论）：WRITE_RESTRICTED 形状作废（0xC0000142 全谱；Administrators 可过启动但写限制失效——P4 直证）、低完整性形状成立（L2/L3/L4 正负对照 + 纯 FFM 打标 + 递归打标义务 + 两洞重述 + 未实测边界 + FFM 订正清单 12 条） |
| `S-a-windows-acl.txt` | S-a 停点（已达）：探针七腿真 VM 取证（真建真限真跑 + 正对照 + 真拒写 + 剥特权嵌套会话）+ 真跑六题 + 打标成本实测 + **两洞实测**（洞 1 成立 / 洞 2 成立且推翻 S-0 推论）+ 突变红/绿对（M1–M3 本机 + M5 VM + 容器门移除）+ 被测类一致性核对（27 类与工作树构建逐字节同构）+ **D4 订正**：`/dev/null` 为「测量项已落地、事实待 S-c 首轮 CI」（本机无 landlock 宿主；边界与选项 A/B 见该档 §7）+ 附录 A/B（收官轮全文 / 红跑片段） |
| `S-b-shell-platform.txt` | S-b 停点（已放行 2026-09-29）：**已落盘**——环境事实 + 载荷保真（构建配方/三向哈希/treehash 66/66 与 VM 同构/被测类一致性）§1–2；pwsh 分派两腿（缺席 fail-loud / 就位 READY）§3；真执行与方言（退出码三题/引号/`$env:`/重定向 CRLF）§4；进程树四腿（timeout/cancel 两形状/timeout-tree）§5；编码 leg9/leg9b（GBK 乱码负对照）§6；fail-closed 腰带 §7；迁移 ids §8；分区与三处复绿（VM 12/1/11 + 本机 18/0/0/5 + 全量 586/0/0/22）§9；突变 M-Sb1–M-Sb6 §10；首跑红现场与产品修正（PATH 大小写）§11；消费方双腿 §12；POSIX 对照 §13；边界 §14；附录 A/B（收官轮全文 / 红跑片段） |
| `S-c-windows-delivery.txt` | S-c 收口（已落盘 2026-09-29）：环境事实 + 载荷保真（三向哈希 / AppleDouble 根因与修法）§1–2；round-3b 全链（构建 exit=0 / 归档 sha / 冒烟 A–H 腿 / analyze.py 16 断言 / 五面类计数 / B-verify 沙箱落点行）§3；r2→round-3b 十二红类表与根因分类 (a)–(f) §4；本机 596/0/0/23 + CI windows job 注册（8 步 + 点名清单核过 missing=[]）§5；GBK 方言事实（G1/G2/D-deny/E-native/F-cmd）§6；挂账与首跑监视项（D4、windows-latest 符号链接特权、归档名 `windows-${os.arch}`）§7 |
| `S-d-release-engineering.txt` | 发布工程核对（12 全量扫描 + notes/迁移路径 + release.md + 附件工作流） |

## 验收（证据 = 实际执行的命令与结果）

- [x] 真 Windows 环境完成安装（归档解压 → `jh.bat --help`/`--verify` 全绿）——S-c 真 VM：`windows-aarch64` 归档解压冒烟 A-help/A2-sessions/B-verify 全 exit=0（stdout 纯 UTF-8 断言过）+ C-probe（镜像内真建低完整性令牌 + 真限 + 真拒写）exit=0；CI windows job 归档冒烟同断言（待首跑）
- [x] 真 Windows 完成任务（keyless：假服务端 SSE 驱动完整 turn（进程内）+ 真 pwsh 工具调用；可选补充：真模型 Windows 跑——VM 无网，未做）——VM：`HeadlessFakeServerE2ETest` 1/0/0/0 + `LocalShellExecutorWindowsTest` 5/0/0/0（真 pwsh 执行/环境透传/超时与取消的进程树击杀）
- [x] 真 Windows 拒批（EOF/审批超时先例）——VM：`HeadlessApprovalTest` 1/0/0/0
- [x] 真 Windows 取消（机制随 S-0 实探定形）——VM：`HeadlessSigintTest` 8 跑 0 红 / 4 跳（跳皆平台门；含真杀进程树腿）
- [x] 真 Windows 恢复（`--resume` keyless 先例）——VM：`HeadlessResumeTest` 3/0/0/0 + `HeadlessCrashResumeE2ETest` 2/0/0/1（1 跳为 POSIX 门）
- [x] 不是只装 pwsh 即算支持（受限档真强制 + PARTIAL 两洞如实 + fail-closed 支路）——VM 冒烟拒绝腿全谱（D-deny 工作区外真拒写 / D2-inside 正对照真写成功 / E-native pwsh 真拒写 / F-cmd 拒写）+ B-verify 落点行点名 windows-acl PARTIAL + 两洞如实（§S-a）+ `WindowsAclTest` 退出码协议（fail-closed 腰带）
- [ ] 已支持平台回归（ubuntu/macos job 双绿 + 归档冒烟不回归）——本机 mac 全量 596/0/0/23 绿（既有 linux/mac 归档与 jlink 冒烟面未动）；CI ubuntu/macos 首跑待 push（与下行同批）
- [ ] 发布工件与稳定面核对齐全（12 全量扫描 + release notes/迁移路径 + release.md + 附件自动化）——S-d 范围，未启
- [ ] 全量 package 绿（本机 + CI 三 job）——本机 ✓ 596/0/0/23；真 VM ✓ 83 类 586/0/0/54（BUILD SUCCESS）；CI 三 job（含新增 windows）待 push 首跑

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| （S-a 提交） | **探针嵌套会话腿首跑红**（真 VM，`leg0 exit=12`）：TEMP 重定向的观测面原本取 `%TEMP%` 下的 `temp-proof.txt`，而嵌套助手退出时**按设计**删掉自己的会话临时目录（`jh-sbx-*`）⇒ 文件随之消失、断言恒搜不到（重定向其实生效——机制无缺陷，是探针自身选错了观测面）。通类风险：以「会自己清场」的对象作断言面 | 观测面改为子进程**报回**的 `%TEMP%` 值记在会话根（`temp-seen.txt`），「往 `%TEMP%` 写文件」排为命令串最后一条（退出码即该条成败）；断言改查「记下的值落在 `--temp` 父下的 `jh-sbx-*` 目录 + 退出码 0」。M5 突变（TEMP/TMP 覆写占位化）证明该断言承载真事实（leg0 exit=12 + leg5 退回宿主 TEMP），还原复绿（`S-a-windows-acl.txt` §2 前史 + 附录 B） |
| （S-a 提交，放行条件项 1） | `WindowsAclTest#hostHelperCommandIsSelfConsistent` 的 `endsWith("/bin/java")` **写死 POSIX 分隔符且无 OS 门**——该断言面的宿主恰含 Windows（本机制真腿唯一落点），Windows 上 `Path` 渲染为反斜杠 ⇒ 首次 windows 真跑必红（与机制无关，纯断言面缺陷；本机 darwin 上原断言恒绿，属假绿掩盖） | 改按 `Path` 语义断言：`Path.of(首参).getFileName()=="java"` + 父目录名 `"bin"`（平台无关，且较原断言多验一层父目录）；突变 M6（期望文件名扰动为 `java-x`）→ 必红（`WindowsAclTest 20/1/0/7`，`expected: "java-x"`），还原后 md5 一致、聚焦复绿 |
| （S-b 提交） | **pwsh 在 Windows 上永不入候选**（真 Windows 首跑暴露，机制其余部分无恙）：`ShellPlatform.pathEntries()` 以 `System.getenv().getOrDefault("PATH","")` 取 PATH，而 `System.getenv()` 的 **Map 视图按精确键名查找**（只有 `System.getenv(name)` 访问器大小写不敏感），Windows 系统变量惯名为 `Path` ⇒ 恒得空表 ⇒ pwsh 探不到、首条命令必 fail-loud（leg0b 在 pwsh 已装且 `where` 命中的前提下仍拒，探针三行直证：`env[Path]=<…>` / `get(PATH)` 有值 / `isRegularFile(pwsh.exe)=true`）。通类风险：大小写敏感的 Map 视图 + 平台惯名差异；本机 darwin 上 PATH 恰为精确大写，单测全绿属假绿掩盖——缺陷只由真宿主暴露 | `pathEntries` 增显式环境映射版（`pathEntries(Map<String,String>)`，`equalsIgnoreCase` 匹配键名），`host()` 传 `System.getenv()`；新增回归用例 `pathEntriesIsCaseInsensitiveAcrossWindowsAndPosixKeyShapes`（Path/PATH/无关键三形态，任意宿主全跑）；宿主探测仍在执行期。突变 M-Sb6（改回精确 `env.get("PATH")`）→ 该用例必红（`Expecting actual: []`），还原 md5 一致复绿 18/0/0/5（`S-b-shell-platform.txt` §10/§11） |
| （S-b 提交，迁移涟漪遗漏 1） | `RealModelAgentE2ETest` 内嵌提示词仍写 "Use the **bash** tool"——工具名改名后真模型 run 会调不存在的工具（该用例在 macOS 本机默认跳过，仅真模型腿可见） | 提示词改 "Use the **shell** tool"；本次同为迁移面（工具名）的随动修正 |
| （S-b 提交，迁移涟漪遗漏 2） | `docs/embedding.md` 两处口径漂移：①15 依赖清单仍列 `shell-bash-local`；②工具面计数「9 个 / 11 个」与实况不符（0.1.0 = 8 个、0.2.0 = 10 个）——0.2.0 迁移指南是消费方对照表，口径错会误导升级 | 依赖清单改新名 + 0.1.0 注记（旧名换用之）；工具面改逐名枚举（fs 5 + shell + todo_write + exit_plan_mode = 8；+ fs_search + ask_user = 10）；新增表行「shell provider 坐标 / plugin id / tool 名（it25 S-b 泛化）」 |
| （S-b 提交，放行条件 1） | `docs/design/README.md:77` 交付清单仍写「Shell seam（Definition + **Bash-Local** Provider + Tool Consumer）」——3a 改名在活文档的**唯一漏迁移**，且该文件在台账声明的同步面内（`docs/design/{02,05,07,10,12,README}`） | 改「Local Provider」（与相邻 FS seam 行同构）；随 S-b 收口提交落 |
| （S-b 提交，放行条件 2） | `AGENTS.md:21`「`shell` 三模块」计数过期——docker 模块加入后实为四模块（shell/shell、local、tool、docker）；该行恰在本次被编辑 | 改「四模块」；计数口径与同段 fs/sandbox 各行的「N 模块」写法一致 |
| （S-c 提交） | **jh 自身输出随宿主编码漂移**：Windows zh-CN 下 `System.out/err` 按 GBK 写出 ⇒ 重定向/管道消费方按 UTF-8 解码得乱码（Linux 非 UTF-8 locale 同病，it24 实测中文降级为 `?`） | 入口 `HeadlessMain.installUtf8StdStreams()` 把 `System.out/err` 换为 UTF-8 `PrintStream`（只覆盖本进程自身输出——外部系统工具字节不在此列）；助手诊断经 `HelperLaunch` 统一注入 `-Dstderr.encoding=UTF-8`；VM 取证 G1（GBK 负对照）/G2（UTF-8 正样）+ 归档冒烟各腿 stdout 纯 UTF-8 断言；`HeadlessStdStreamsTest` 2/0/0/0 |
| （S-c 提交） | **命令含引号即两跳失真**（S-0 首跑 e2e 之谜根因）：`pwsh -Command <裸文本>` 经 provider→助手 hop-1（宿主 JVM `ProcessBuilder` LEGACY/WIN32_SAFE 两口径互斥——吃引号劈段 / 泄漏 `\"` 字面）后命令根本没执行（探针 P6/P7：pwsh 腿 exit=1、stderr 空）；助手 `-- <argv...>` 逐参协议同病（P1/P9） | 载体化：命令文本走 `-EncodedCommand`（UTF-16LE base64 单 token，`ShellPlatform.encodedCommand`）；助手 argv 走 `--argv-b64` 单参 blob（4B BE 长度 ‖ UTF-8 串联再 base64；`WindowsAcl.encodeArgv/parse`），无引号无空白即两口径均字节保真（P10 实测 + VM D-deny/D2-inside 端到端）；旧 `--` 形态与坏 blob 一律 fail loud 不猜（`runArgsCarriesArgvAsOneQuoteFreeBase64Token` / `malformedArgvCarriersAreRejectedNotGuessed`）；文案面随动（05 §6 / 02 / 12 §6 / module-info / ShellRequest / ShellToolPlugin） |
| （S-c 提交） | **zh-CN 宿主 `sandboxDenied` 漏标**：拒绝文由原生工具按宿主本地化产出（cmd「拒绝访问」= GBK 字节），消费侧仅按 UTF-8 解码 ⇒ U+FFFD 使签名不匹配、标记落空（S-b leg9b 六个替换字符即现场）；本机 UTF-8 宿主全绿属假绿掩盖 | `LocalShellExecutor.matchesDialect` 改按 stderr **原始字节** × 候选解码集（UTF-8 + `native.encoding` 解析出的非 UTF-8 编码）逐行匹配（`dialectCharsets`；纯函数面 `LocalShellDialectTest` 5/0/0/0）；真 VM 端到端 `SandboxLocalTest#windowsAclCommandDeniedOutsideStillMarksSandboxDenied`（真拒写 sandboxDenied=true + 授权正对照） |
| （S-c 提交） | **Windows 路径反斜杠拼 JSON = 非法转义**：示例与工具 e2e 侧手拼 `{"path":"…"}`，`C:\U…` 的 `\U` 是非法 JSON 转义 ⇒ 真 Windows 解析必败（`SpineMain` / `ProductionScenarioTest` / `FsToolEndToEndTest`）；本机 `\` 不存在故原样通过——缺陷只由真宿主暴露 | 注入侧增 `jsonPath`/`escaped`（`\`→`\\`）后拼串；三处随动 |
| （S-c 提交） | **fs 结果路径随平台分隔符漂移**：`LocalFs` 相对路径 `toString()` 在 Windows 产出 `\`——同一棵树输出面随宿主而异，跨平台消费（JSON/工具结果）不可移植 | 归一为 `/`（`replace(File.separatorChar,'/')`，契约注释明文化） |
| （S-c 提交，断言面簇） | **写死 POSIX 方言/平台观测面的断言在真 Windows 全红**（机制无恙）：`HelperLaunchTest`/`LandlockTest` 写死 `/bin/java`；`AppBootTest` 伪平台断言依赖「win32 链必非 Ready」（真 Windows 上助手可启动 ⇒ Ready，假红）；`StreamRendererTest` 按 `\n` 比 `println` 输出（Windows = CRLF）；`SandboxLocalTest` SBPL 断言未过产品 `sbplString` 转义口径；`DockerShellTest` 以 `Path.of("/")` 造越界路径 | 各按 `Path` 语义 / 平台无关观测面改写：`Path.of("bin","java").toString()` 断言；`AppBootTest` 注伪 `java.home`（不存在目录，只被 HelperLaunch 调用期读）使链在任何宿主确定性非 Ready——锚点（非 Ready ⇒ 预警且 verify 仍过）同形可验；CRLF 归一；SBPL 断言随产品转义；`workspace.resolveSibling(...)` 造越界路径 |
| （S-c 提交，门/构建簇） | ①`SandboxLocalTest` 注伪助手仲裁组（写死 `/bin/sh` 形态 + `Path.of("/")` 哑根，产品不可达）与 `WindowsAclTest` 两处 MAC 专腿在 Windows 上无意义跑/假红；②**spotless 行尾在无 `.gitattributes`/无 `.git` 的源码 tar 形态退化为 PLATFORM_NATIVE** ⇒ Windows 上期望 CRLF、LF 源码全量假红（VM run-2 首跑实证） | ①按平台门分区（`@EnabledOnOs({OS.MAC, OS.LINUX})`，类注写明归因；Windows 真机制由 `WindowsAclTest` 覆盖）；②根 POM spotless 钉 `<lineEndings>UNIX</lineEndings>`（= .editorconfig 口径，注释互指） |
| （S-b 提交，派生发现） | 分区清点原列四类，实探又见**第五类**：`HostileInstructionsTest` 的建链腿（`ln -sf` + 符号链接读平权断言）在 Windows 上必红（POSIX 文形；Windows 建符号链接需特权/开发者模式） | 该用例加方法级 POSIX 门（`@EnabledOnOs({OS.MAC,OS.LINUX})`，附理由注释：工具的 Windows 真腿归 S-c CI job）——**S-b 新加**。归因澄清（防误读）：`HeadlessCrashResumeE2ETest#assumePosix()`（it19，d3bab74）与 `DockerShellTest` 方法级门（it12.5，4371da2）是**既有设施**，S-b 只做改名随动（前者 `shell` 文案与 SSE 工具名）与 `DockerShellTest#cleanOrphanedContainers` 去 sh/xargs（改 Java 侧拆分，Windows 宿主无 sh）；「四类已分区」的**净状态**为真，不得读作 S-b 新加全部门 |
| （S-b 取证工具，不改产品） | 证据侧 treehash 两处不可比（同一批 .class 在两宿主摘要不同）：①相对路径分隔符 `\\`/`/` 直接入摘要；②按 `Path` 自然序排序（WindowsPath 大小写不敏感 vs UnixPath 逐字节） | 汇总前分隔符归一并改为**按行文本排序**，另加 `treelist` 腿逐文件可 diff——修后 VM/本机摘要一致（`83a09b17…`）、66/66 行全同（`S-b-shell-platform.txt` §2） |

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
| `05 §6:474-478` win32 机制段（WRITE_RESTRICTED 受限令牌 + per-workspace SID 常设授予 + per-session 随机 SID）与「四确认」内容 1 同句 | 形状改为**低完整性（Low IL）**：`CreateRestrictedToken(DISABLE_MAX_PRIVILEGE, 0 SID)` + `SetTokenInformation(TokenIntegrityLevel)`；两 SID 机制退役；可写面 = 低标签面（会话可写区需逐对象打标 + 还原）；两洞重述（洞 1 = 「可写面 = 一切 Low 标签对象」、洞 2 归待实测） | S-0 真 Windows 实探直证：WRITE_RESTRICTED + CREATE_NO_WINDOW 子进程一律 0xC0000142（令牌/授权全 OK），唯一可过启动的受限 SID（Administrators）令写限制形同虚设——既定形状物理不可用（`S-0-windows-acl-shape.txt` 轮次 8–12） | 迭代内已登记（「S-0 首跑实探结论」节 + 本表）；**05 §6 机制/两洞段已在 S-a 同停点改写**（低完整性 + 逐对象打标 + 两洞实测；同步面随动：02 模块表、`bundle.yml` 注释、README×2 平台段、AGENTS.md 现状/layout、`AppBoot` NoBackend 出路文案——口径反转「等待 windows-acl」已淘汰；两处 it24 漏同步顺手补齐：02 的 linux 链、05 的 `/dev/null` 推论口径） |
| `05 §6:497-500` landlock `/dev/null` 口径（「被拒（EACCES）……allow-list 语义的直接推论，未实测」） | 口径改标「**推论待实测**」+ 点名测量项 `SandboxLocalTest#landlockDevNullWriteIsMeasuredForAdjudication`（正对照 + 放行/拒绝两分支取证、不带预判）；事实仍未取得——**本机无 landlock 宿主**（docker 内核 6.4.16-linuxkit 未编译 landlock ⇒ ENOSYS；无 lima/colima/multipass/qemu），唯真 landlock 宿主（CI runner，ABI v7）可出 | `WritableRoots` 不含设备节点是设计事实，但「因此 landlock 腿必拒 `/dev/null`」只是推论；D4 裁决「出事实再裁，不带预判」 | 迭代内已同步（文档口径 + 测量项落地）；**选项 A（维持拒绝、文档化差异）/ B（设备节点例外，不动 `WritableRoots`）于 S-a packet 呈报待裁**；事实随 S-c 首轮 CI 回填后终裁（S-c 的 CI 断言清单须把该用例名列入「真跑了且没跳过」） |

## 后续（本迭代不做，记此）

- **S-c 监视项（方言端到端复验）**——**已于 S-c 落地（2026-09-29）**：`LocalShellExecutor` 改按 stderr 原始字节 × 候选解码集（UTF-8 + `native.encoding`）匹配；真 VM zh-CN（GBK）形态端到端真命中（冒烟 D-deny 拒写腿 + `SandboxLocalTest#windowsAclCommandDeniedOutsideStillMarksSandboxDenied`）；细节见「修正」表 S-c 方言行。
- **协议词表共享抽取**：landlock 与 windows-acl 助手的退出码词表与「末行结论」解析面现为两份——0.2 窗口外另行排期。
- `WindowsAcl.Invocation` **sealed 化**（现为单个 record + `probe` 布尔判别；类型纪律 08 的后续项）。
- `WindowsAcl.wide()` **改名**（UTF-16 缓冲助手，命名待细化）；`isReparsePoint` **死参清理**（`capture` 参数未被使用——函数自建 capture 段）。
- **洞 2 消除**（NTFS 硬链接别名越界）：0.2 不做既定——检测需 link count（`GetFileInformationByHandle`/`FileStandardInformation`），修法（跳过或拒绝）与 `WritableRoots` 语义同议，另行排期；当前如实外显（`Ready.detail` + 05 §6）。
