# it30 停 3 审查包：Consumer + 装配面 + 验收

**新增模块自问（首行必答）**：是——jobs/tool 新模块 + base 加两行。四件套逐项勾：①META-INF/services 双通道（Plugin×3 + Codec×1）✓ ②bundle/base test 域 + headless 编译域承载 ✓ ③headless 钉死面（verify 计数 30/37→32/39 + jobs 行；敌意测试工具清单 +4）✓ ④dist/jh moditect 模块清单三行 ✓（jlink 冒烟实撞后补）

- **不变量增量**：四工具恒需审批（无豁免出口）；cwd 显式绝对（shell 同立场，无默认执行边界）；读三态逐字措辞（「暂无更多（仍在运行）」≠「任务没有输出」、截断≠读尽、Lost=失联非失败）——禁词突变必红在案；工具层轮询（waitSeconds）不进 seam 原语。
- **裁决点**：①读上限单键 maxReadBytes（B31 缺省 256 KiB，游标语义使上限只影响单片）；②缺席态证据口径：呈现态 = jlink `--verify` EXIT=0 + jobs 四工具行，缺席态 = 行移除即 tools=0（双态代码路径沿 lsp/web 先例，未单独起镜像负例——如实登记，可按裁决补）。
- **风险自报**：W1 fold 端到端的证据形态 = 双宿主探针宿主 B 的 fold（真持久化载回 + JobSnapshotCodec 解码 + 按 id 末值胜 → Lost）+ codec 五态往返测试——是「W1 消费形态」的最小等价预演，非 W1 本体；正式投影层留 W1 迭代。
- **证据入口**：[双宿主探针 + jlink](stop3-two-host-lost-probe.txt)（1/0/0 + verify EXIT=0）；[模型在环](stop3-model-in-loop.txt)（用户真 key，含漂移增强样本）；措辞禁词突变红绿（本轮实现批）；工具/配置测试 9 绿。
- **若只能改一处**：job_read 的 fromByte/maxBytes 参数面（是否够模型稳定往返游标）——模型在环已实证 5 步链一次走通，我方立场是当前面足够。

## 1. 三行映射
| 设计增量 | 实现 | 测试 |
|---|---|---|
| 四工具双态注册 | JobsToolPlugin（resolve(JobService.KEY).ifPresent） | 敌意测试工具清单 +4（headless） |
| 三态措辞纪律 | JobToolOutput（readMarker/stateWord） | JobToolOutputTest 6 项 + 禁词突变 |
| 装配四件套 | services/两承载/钉死面/moditect | AppBootTest 23 绿 + HeadlessVerifyTest + jlink verify |
| 验收四场景 + R1 | 探针（宿主 A/B）+ 模型在环 | TwoHostLostProbeTest 1/0/0 + 用户实录 |

## 2. 偏离清单
| 偏离 | 为什么 | 处理 |
|---|---|---|
| 双态缺席态无独立镜像冒烟 | 行移除负例成本高；代码路径沿先例 | 如实登记，可按裁决补 |
| 模型在环 cancel 走 no-op 分支 | 任务先于取消自然完成 | 漂移增强样本（用户裁决：等价更强） |

## 3. 若错靠什么发现
措辞禁词突变必红（模板钉死）；游标误用会在模型在环多轮往返中断链；Lost 语义错在探针四断言红；四件套漏一件 = CI/jlink 即红（本轮两次实撞即证）。
