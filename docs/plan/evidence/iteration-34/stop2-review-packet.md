# 停 2 审查包：--workbench 全屏 TUI（渲染/按键/端子薄壳 + CLI 接线 + 装配四件套）

状态：**修订版已出包待复审**（评审 F1/F2/F3 三项已处置 + 三项铁证，见 §5 修订记录）。

**本停是否新增模块 / base 加行**：新增模块 `workbench/tui`——纯库模块（非 capability 三角、非插件、无 ServiceLoader 注册）⇒ **bundle base 无新行**；装配面全部在镜像侧：dist/jh moditect 四行 + launcher 烧入（antrun），不进组合行。`--verify` 计数不变（34 rows / 41 discovered / 0 unreferenced）双面取证：HeadlessVerifyTest 8/8 断言 + 镜像 keyless 真跑同计数。

## 1. 交付物

| 文件 | 角色 |
|---|---|
| `workbench/tui`（it34 W2 新模块） | 渲染纯函数（`WorkbenchRenderer` 五视图帧 + 状态栏/footer/切片/clamp）+ 按键解析（`KeyParser` ESC 状态机，1-5/↑↓/PgUp/PgDn/q/Ctrl-C 词表）+ 状态迁移（`WorkbenchState` 纯函数）+ 端子薄壳（`TermIo`，JLine 4 唯一触点；`checkedOpen` 哑终端判定——dumb/dumb-color 两型 fail loud「无真 TTY」；`KeyInput`/`ScreenOutput`/`WorkbenchSource` 三 seam 全注入） |
| `examples/headless` | CLI 接线：parse 互斥矩阵（`--follow` 必配 `--workbench`、`--workbench` 必配 `--session`、与任务文本/`--resume`/`--verify`/`--sessions`/`--session-view` 互斥）；dispatch 在 API key 检查之前（只读旁路无 key 可跑）；`workbenchSource` 两态（检视态 load→投影一次定值快照 / 跟随态 tail 每 poll 全量重投影）；`runWorkbench` 词表（未知 id / 写者锁冲突 exit 3、无真 TTY exit 2）；`HeadlessWorkbenchTest` 9 测 |
| `dist/jh` | moditect 四行（`workbench.tui` + `org.jline.terminal` / `.terminal.ffm` / `.nativ`）+ antrun package 相位后置替换 launcher 烧入 `--enable-native-access=org.jline.terminal.ffm,org.jline.nativ`（sh/bat 双执行体，failOnNoReplacements fail loud；replace 后 chmod 755 恢复执行位） |
| 文档 | `stop2-evidence.md`（取证）、02/12/AGENTS/README/current-state 同步、iteration-34 §2/§3 与 preflight 订正 |

## 2. 不变量增量（→ 可观察断言）

1. **只读旁路**：工作台不进 API key 检查、不建 agent、零写路径——keyless 镜像实跑 exit 3 会话诊断（§4 证据）。
2. **两态 seam 界限**：检视态 = load→投影一次定值快照（`() -> views`）；跟随态 = tail 无锁流式读面每 poll 全量重投影（位置推进不重开）——M1/M2 突变分别击杀两态装配。
3. **渲染纯函数**：同输入必同输出、恰好 rows 行、每行 ≤ cols——M-a 状态栏 8 击杀。
4. **按键词表**：ESC 状态机解码、未完成 null、未知尾 Ignore 复位不污染——M-b 'q' 词表 4 击杀。
5. **状态迁移纯函数**：Quit 停跑、Switch 复位滚动保留 follow、滚动 clamp——M-c 单点精确击杀。
6. **exit 码词表与 `--session` 同源**：未知 id / 写者锁冲突 exit 3（诊断走 LOG）、无真 TTY exit 2——M5/M7 击杀。

## 3. 验证取证（全文见 [stop2-evidence.md](stop2-evidence.md)）

- 聚焦：HeadlessWorkbenchTest 9/9 + HeadlessVerifyTest 8/8（TDD 红绿，RED = 编译级功能缺失）。
- 突变：S3 面 M1–M7 + S1/S2 面 M-a–M-c 复跑，全部破坏必红→git checkout 还原复绿（十例全闭环）。
- 门禁：validate EXIT=0（0 checkstyle）+ 全反应器 test BUILD SUCCESS 3:46（新鲜 179 XML = 1399/0/0）；tui 模块 58/58（修订批 TermIoTest 8→11）。
- 镜像：烧入行在镜、五模块在镜（含 `org.jline.nativ` 全平台 JNI 资源面）、`--version` EXIT=0、未知 id EXIT=3（keyless）、keyless `--verify` EXIT=0 计数不变、`--help` 广告三行。

## 4. 诚实交易（已知项）

- **派发顺序突变单元层不可观察**：测试经 seam 直测不进 `main()` 派发——换序（workbench 旁路 vs key 检查）单元套件无信号，不伪称有测试钉死。实跑面有真实观察：keyless 未知 id 出 exit 3（会话诊断）而非 exit 2（缺 key），顺序正确由实跑背书。
- **preflight 订正（证据后置偏离）**：W2 开工 preflight 断「terminal 模块描述符无 requires 边 ⇒ 排除 jline-native」被 S3 jlink 镜像实测推翻（describe-module 含非 static `requires org.jline.nativ` + 字节码直引；javac 惰性不报错而 jlink 全图解析必败）。处置：移除排除 + moditect 加行，iteration-34 §2/§3 与 [preflight-jline-460.txt](preflight-jline-460.txt) 已订正。
- 真 TTY 面（五视图全屏实录 + 跟随态 live tail + launcher 零警告取证）与 `.bat` 烧入（Windows 档案随 profile）留停 3 / CI 腿。

## 5. 修订记录（评审处置 F1/F2/F3 → 三项铁证，全文见 [stop2-evidence.md](stop2-evidence.md) §6）

| 项 | 缺陷 | 修订 | 铁证 |
|---|---|---|---|
| F1（阻塞） | 烧入清单只授 `org.jline.terminal.ffm`——真调受限方法（`System::load`）的是 `org.jline.nativ`，每次启动 4 行 WARNING | sh/bat 双面烧入值 → `--enable-native-access=org.jline.terminal.ffm,org.jline.nativ` | ① 重建镜像后三面（--version / --verify / unknown-id --workbench）stderr 零 WARNING（grep 计数 0） |
| F2（阻塞） | 「无真 TTY exit 2」死码：JLine 退化环境建哑终端不抛 | `TermIo.checkedOpen` 判定（TDD：RED 编译红 → GREEN 11/11 → 突变 1 红 → 还原复绿）——dumb/dumb-color 两型先关端子再抛 `IOException`「无真 TTY」 | ③ 哑终端双环境（os.setsid 无控制终端 / TERM=dumb 管道）EXIT=2 + stdout 0 字节 + 指引词面逐字；真实会话 id 证明 load 后恰由端子判定触发 |
| F3（非阻塞） | antrun replace 剥执行位（`jh` 600，直接执行 126） | replace 后补 `<chmod perm="755">` | ② `ls -l` 实测 `jh` = 755（与 java/keytool 同），`./bin/jh --version`（非 sh）EXIT=0 |

修订为增量提交（评审要求：四笔留置不动）；修订前 1399 测试与十例突变记录仍有效（改动面不触已测面）；`.bat` 面随 Windows 档案走 profile，本地无 .bat 生成，留 CI Windows 腿。

## 6. 请裁决

- 放行停 2 → 开工停 3（真机 TTY 冒烟 + 收口）。
- 若指出缺陷 → 修订后重出 packet。
- 停 2 三笔提交（`3991a1f` 实现+同步面、证据/iteration-34 更新、修订批）均不 push（迭代仪式：收口时随批）。
