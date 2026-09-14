# 迭代 13 — 可运行产物：dist（jlink）+ CLI 完备（状态：进行中）

模块：`dist`（新增聚合 + `dist/jh` jlink 编排）、`examples/headless`（CLI 完备 + 会话 id）、`.github/workflows/ci.yml`（镜像冒烟）、文档（02/07/README/AGENTS）

四确认日期：2026-09-14（用户确认：dist 入口 = `examples/headless` 直接 jlink；jlink 接线 = moditect-maven-plugin（新增第三方构建插件获批）；headless 会话 id 修正纳入本迭代；it12.5 挂账两处订正随本迭代；四确认照草案落盘并开工）

## 四确认

- **内容**：
  1. **dist（jlink）**：新增顶层 `dist/` 聚合 + `dist/jh` 编排模块（pom packaging，无运行时代码）。moditect-maven-plugin `create-runtime-image` 绑 package 阶段：模块路径 = 本 reactor 对应模块 jar + jackson + snakeyaml（全部已模块化，不需 add-module-info），`--add-modules io.javanatic.harness.examples.headless`，`bindServices=true`（ServiceLoader 插件面在镜像内可达——冒烟 `--verify` 是判据），launcher `jh`。产出 `dist/jh/target/jlink-image/bin/jh`；只产本平台镜像。
  2. **CLI 完备**（`examples/headless`）：
     - `--help`：全 flag 用法 + 示例，stdout，exit 0；未知参数/缺值从裸堆栈改为「ERROR + 指向 --help」，exit 2
     - `--workspace=<dir>`：须已存在目录（fail loud，exit 2），贯通 fs-local root / shell-tool workspace / sandbox-policy workspace 三处 pin；缺省仍为临时目录
     - `--approval=auto|ask|deny`：按值 overlay 审批三 Provider（启用其一、禁用另二）；`--policy=` 治理档语义不动
  3. **会话 id 去硬编码**：`Session.newId("headless-1")` → 运行 id 带时间戳/短随机，运行时打印；`--resume` 按打印 id 续。与 07 §7 设计稿对齐（设计稿本就是时间戳 id）。
  4. **挂账订正（it12.5 记录，本次确认）**：AGENTS.md「跑一个 task」改 dist 形态并补 `--docker/--image`；「用系统 Maven 3.8+」改准确（CI 用系统 maven；本机无系统 mvn——IDE 自带 maven + temurin-25）；07 §9 换 Provider 示例重写（sandbox 配置不属 shell provider config，与 it12 逐调用 resolve / it12.5 换 Provider 语义一致化）。
  5. **CI 冒烟**（双 job）：镜像随 package 自动构建；加 `bin/jh --help` 与 `bin/jh --verify`（无 key）两步。
  6. **文档同步**：02 模块清单加 Distribution 层；根 README 结构树 + 路线表 + 运行段；AGENTS.md 见 4。

- **目标**：路线里「可交付」的兑现——解出镜像即可 `bin/jh` 一条命令运行，无需手拼 module-path；CLI 自解释（--help）；安全边界（工作区）与审批 Provider 在命令上显式。

- **为什么**：① 今日唯一运行路径是手拼二十来项 module-path 的省略号命令（README/AGENTS 都写不下去）——「可交付」与现状的最大摩擦，jlink 是 JPMS 投资的兑现。② 工作区是沙箱授予面的实体（fs 围栏 = shell workspace = sandbox 授予面 = 同一目录），今天却是隐式临时目录。③ 审批三 Provider it7 已备，headless 却只能改 profile YAML 换——CLI 补最后一轴。④ 停点协议（本日刚落盘）首次实战。

- **不做**：
  - REPL/交互面（it14）
  - jpackage/安装器/Graal native image
  - 压缩归档（tar.gz/zip）、签名、Central 发布、门面冻结（it16）
  - it12.6 其余硬化项（JSONL fsync/撕裂尾、LlmError 分类、LocalFs realpath、`--verify` 无后端预警、LoopGuard 注释）
  - 跨平台交叉构建（dist 只产本机镜像；CI 双 job 各建各的）
  - `--budget=`：PRODUCTION 档在 headless 仍因 loop-guard 默认 budget=0 被拒（`--approval=` 只消 AUTO 一项违规）；完整可达随生产模拟归 it15
  - 公开 API 破坏性变更（CLI 只增 flag；seam 不动）

## 设计增量（ADDED / MODIFIED / REMOVED）

- ADDED：
  - Distribution 层：`dist/`（聚合）+ `dist/jh`（artifactId `harness-dist-jh`，jlink 编排；02 模块清单新增一节）
  - CLI 契约：`--help` / `--workspace=` / `--approval=auto|ask|deny`；未知参数 exit 2
  - 运行 id 契约：`headless-<时间戳>-<短随机>`，运行时打印（07 §7 从设计稿变为实现）
- MODIFIED：（旧 → 新）
  - AGENTS.md「跑一个 task」：`java --module-path …` → `dist/jh/target/jlink-image/bin/jh …`
  - AGENTS.md Commands：「无 mvnw wrapper，用系统 Maven 3.8+」→ 补 CI/本机两态
  - 07 §9：`shell-bash-local config.sandboxMode` 示例 → 行替换 `shell-docker` / `sandbox-policy` 行改档
- REMOVED：无

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `examples/headless/…/HeadlessMain.java:parse/RunnerOptions/main/run` | help/workspace/approval/id/出口码 | ✓ |
| `pom.xml:<modules>` | 加 `dist` | ✓ |
| `.github/workflows/ci.yml` | 加镜像冒烟步骤 | ✓ |
| `docs/design/02-module-layout.md` | Distribution 节 | ✓ |
| `docs/design/07-profile-bundle.md:§9/§7` | 示例订正 + 运行 id 设计稿核对 | ✓ |
| `README.md`（结构/路线/运行段）、`AGENTS.md`（跑一个 task/Commands） | 同步 | ✓ |

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行，全机械迭代写「无」）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| S1 CLI 面 | `HeadlessMain`（parse / RunnerOptions / help / workspace / approval / 运行 id / 出口码）+ 聚焦测试 | 已放行（`8d3159f`）|
| S2 dist + launcher | `dist/` 两 POM、根 pom、moditect 参数、ci.yml 冒烟 | 已放行（`3254d93`）|

## 验收（证据 = 实际执行的命令与结果）

- [ ] `mvn -B -q package` 全 reactor 绿（含 dist）且 `dist/jh/target/jlink-image/bin/jh` 存在
- [ ] `jh --help` exit 0（stdout 含全部 flag）；未知参数 exit 2 且指向 --help；`--workspace=` 指向不存在目录 exit 2
- [ ] `jh --verify` exit 0（无 key）；`jh --verify --approval=deny` exit 0；`--approval=bogus` exit 2
- [ ] `jh --workspace=<真实目录>` 跑一条写入任务：文件落该目录、沙箱拒绝目录外写（证据：运行输出）
- [ ] 真跑（本机 DEEPSEEK_API_KEY）：`jh "任务"` 完成、两次运行各得独立会话（不混账）+ 按打印 id `--resume` 续跑（seq 续接）
- [ ] `--docker` 经镜像形态仍可跑（本机 docker 在）
- [ ] 双 job CI 绿 + 冒烟步骤过（push 后取证 run id）
- [ ] 文档同步：02/07/README/AGENTS 更新在案；挂账两处订正完成

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
| 四确认 1：`dist/jh` 为「pom packaging，无运行时代码」 | `packaging=jar`（产空 jar，不进镜像） | moditect 1.2.2 `CreateRuntimeImageMojo` 前置校验先解引用 `primaryProjectArtifact.getFile()`，pom packaging 该值为 null → NPE（以插件源码核验） | 迭代内已同步（POM 注释写明 jar 是插件入口前提） |
| 四确认 1：`bindServices=true`（ServiceLoader 插件面在镜像内可达） | 不设 `bindServices`；显式列 7 个 jlink 根（headless + core.plan/todo/preset + sandbox.local/policy + shell.docker） | 本机 temurin-25 无 jmods（JEP 493），jlink 对无打包模块的 JDK 拒 `--bind-services`；插件可达性由「根模块在场」决定，与 bind 无关 | 迭代内已同步（POM 注释；根清单漂移由镜像内 `--verify` 兜底——bundle.yml 行引用的插件 id 缺根即 boot 失败） |
| 未提及（jlink 实验发现）：镜像内 `--verify` 抛 `ClassNotFoundException: java.beans.IntrospectionException` | `bundle/base` 显式 `requires java.desktop` | snakeyaml 描述符对 java.desktop 仅 `requires static`，默认 `Yaml()` 构造走 java.beans 内省，镜像未解析 java.desktop 即崩；需求属 snakeyaml 的边界模块 | 迭代内已同步（由 bundle.base 承担，不在 dist POM 里补模块清单） |
| 未提及：launcher 的 `<mainClass>` 被忽略、JDK 无法给模块写主类 | `<module>` 取值 `…examples.headless/…HeadlessMain` | moditect 1.2.2 `Launcher` 只拼 `name=module`；javac 无 `--main-class`（ModuleMainClass 写不进）；`module/mainClass` 是 jlink launcher 唯一可行语法 | 迭代内已同步（POM 注释） |
| 未提及：`jarInclusionPolicy` 默认 null → `copyJars` NPE | 显式 `NONE` | moditect 1.2.2 对 null 策略无守卫，枚举只有 NONE/APP/APP_WITH_DEPENDENCIES | 迭代内已同步（副作用：镜像残留空 `jars/` 目录，无害） |
| 未提及：镜像体积与模块面 | 未压缩 90M；`bin/` 含 `java/jh/jwebserver/keytool`（`jdk.httpserver` 因 headless 测试面 `requires` 进镜像） | 先要正确可跑；压缩与模块收窄是优化，非本迭代验收面 | 挂账（`<compression>` 开关 + jdk.httpserver 收窄候选，随后续硬化或发布切片） |
| 07 §7 草图：`java -jar headless.jar --dump-config` 等旗标 | CLI 实装旗标集为 `--help/--workspace=/--approval=/--verify/--resume=` 等；无 `--dump-config`（`AppBoot.dump` 助手存在，CLI 未暴露） | 草图自 it7 起为设计态；本迭代 CLI 完备的范围按四确认（help/workspace/approval）落定 | 迭代内已同步（§7 草图与命令块订正为实装集） |
