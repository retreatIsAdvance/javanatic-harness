# 停 3 审查包：真机 TTY 验收 + Follower 关闭钩子（W2 收口前最后停点）

状态：**已放行**（复审结论：放行——A–G 全部落地 + 残留面干净，见 [stop3-review-verdict.md](stop3-review-verdict.md) 归档）。收口中：验收清单收尾 + 全批 push + CI 四腿 + run 号回填。

## 1. 交付物

| 文件 | 角色 |
|---|---|
| `workbench/tui/…/WorkbenchSource.java` | `extends AutoCloseable` + `default close()`（检视态空实现;跟随态语义 = 关 tail 游标——停 2 交付的 source seam 无 close 生命周期面,W3 长驻消费前置缺口,补对称形状装配透传） |
| `examples/headless/…/HeadlessMain.java` | 跟随分支匿名类透传 `follower.close()`;`runWorkbench` 改 `try (io; source)`（source 先关、io 后关） |
| `…/HeadlessWorkbenchTest.java` | +1 测 `followSourceCloseReleasesTailCursor`（child scope 注入计数 wrapper,父链取真实现）——10/10 |
| `stop3-evidence.md` | 真机冒烟取证（§1 TDD / §2 镜像 / §3 PTY 双态 / §5 诚实交易） |

## 2. 不变量增量（→ 可观察断言）

1. **跟随态 source 生命周期完整**：close() 透传 Follower.close()——突变摘除透传 → 计数断言单点必红（今天是空操作,W3 赋义时装配已就位,不会成为隐性泄漏）。
2. **真终端面五视图可用**：检视态键 1-5 逐一切换命中（turns 缺省）+ q 退出 EXIT=0——PTY 真控制端子（对照哑终端双环境 exit 2,停 2 修订铁证③的反面）。
3. **跟随态 live tail 实跑**：`events=2 → 外部追加 seq 2/3 → events=4`（屏面事件数增长 + 追加行入窗）,`[follow]` 标记与 footer 逐字,EXIT=0——四契约的实跑面（无锁/完整行/seq 单调）。
4. **真终端零 WARNING**：两态 PTY 冒烟捕获流 WARNING=0——F1 双模块烧入在真终端的生效面（非哑/管道环境）。

## 3. 方法学声明（PTTY = 真终端）

`pty.fork()` 给子进程 setsid + 真控制端子（stdin/out/err 全接 slave、`TERM=xterm-256color`）——JLine 视角与真人终端同面;驱动脚本（`/tmp/it34-stop3/`,非仓内工件）喂键、收屏、取退出码。非真人值守,但端子语义为真（raw mode 真实进入、能力探测真实发出）。

## 4. 验证摘要（全文 [stop3-evidence.md](stop3-evidence.md)）

- TDD：RED 编译红逐字 → 首实现踩 AutoCloseable 一坑（只加 default close 不够,编译红如实记）→ GREEN 10/10 → 突变单点红 → 还原复绿。
- 镜像 16:39 重建：执行位 755 保持、烧入逗号清单保持、三 JLine 模块 + workbench 两模块在镜、keyless `--verify` 34/41/0 不变。
- 检视态：五视图全 HIT、`events=1593`、EXIT=0、WARNING=0。
- 跟随态：`events=2→4`、追加行入末帧、footer 逐字、EXIT=0、WARNING=0、夹具清理零残留。
- 全反应器 `mvn test`：[stop3-closeout.md](stop3-closeout.md)（本停点落盘）。

## 5. 诚实交易

- 外部追加模拟写者（seam 使写者身份对读者不可观察;真写者对照面有单测钉死）。
- 夹具 header 复用主会话（显示 id = header id,查找 = 目录名——既有语义如实记）。
- 探针首跑两 MISS 为词面错（跟随态缺省 timeline）,非产品缺陷。
- `.bat` 烧入与 CI 四腿：随收口批 push 取证（本地 macOS 无 .bat 生成）。
- JLine 启动期端子能力探测序列（Kitty/DA1）真实发出,无人应答超时继续——无害,如实记。
- 关闭钩子出处原引「停 2 评审 P3 遗留」无法追溯（停 2 评审归档仅 F1/F2/F3,无 P3 条目）——修订批订正为可追溯出处并登记 iteration-34 修正表;**复审已补档**：P3 原文出自停 2 修订版放行裁决附言第 2 条,已补进 stop2-review-verdict.md 附注（标注「放行裁决附言，2026-11-13 补档」）,链条闭合。

## 6. 修订记录（停 3 修订批,2026-11-13）

| 项 | 缺陷 | 修订 |
|---|---|---|
| A | closeout 待推批次手工枚举 7 笔,漏列停 1 两笔（`9b6cf22`/`de828ea`）——按清单推收口批会漏推 | 清单补全 9 笔 + 本修订批 = 10;批清单以 `git log --oneline origin/master..HEAD` 为准 |
| B | AGENTS.md 现状行烧入值未随 F1 更新（旧单模块值） | 订正逗号清单 + 补 F3 chmod 755 面 |
| C | iteration-34 §3 正文与 preflight 烧入方案仍是旧单模块值（同文档口径冲突） | 订正为逗号清单 |
| D | 关闭钩子出处引「停 2 评审 P3 遗留」无法追溯（归档仅 F1/F2/F3） | packet / evidence / 测试 Javadoc 三处出处订正;修正表登记;pits 回填;复审补档——P3 原文（放行裁决附言）入 stop2-review-verdict.md 附注,链条闭合 |
| E | 请裁决节笔误「验收清单两项勾结」 | 「两项收勾」 |
| F | current-state 进行中段仍写停 2 出包待评审 | 更新为停 3 修订版出包待复审 |
| G | closeout 陈旧件只具名 AskVisibilityProbe | 补具名 IZProbeTest（Oct 13,同类陈旧） |

## 7. 请裁决

- 放行停 3 → 进 it34 收口（验收清单两项收勾 + 全批 push + CI 四腿 + run 号回填）。
- 若指出缺陷 → 修订后重出 packet。
- 本停点两笔提交（关闭钩子实现 + 取证/同步面）不 push（迭代仪式：收口随批）。
