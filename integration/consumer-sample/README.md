# consumer-sample —— 外部 Java 接入示例（非 reactor）

演示一个**不读主仓源码**的外部 Java 工程如何只凭发布坐标完成三件事：

1. **嵌入 Agent**：`AppBoot.compose / boot` 起 `Runtime`（`bundle/base` 已导出 `io.javanatic.harness.boot`）；
2. **自注册插件与工具**：实现 `Plugin`（经 `META-INF/services` 声明被发现）+ `ToolDefinition.of(...)`
   + `registry.register(scope, def)`；bundle 经 classpath 资源 `META-INF/harness/bundle.yml` 被 profile 以
   **bundle name** 引用（本工程自带 `name: consumer-sample`，profile 写 `bundles: [base, consumer-sample]`）；
3. **治理自证（keyless）**：`compose` → `dump` → `boot()` 通过；坏组合由 `boot()` fail loud。

本目录**不在任何 `<modules>` 中**（不进主仓 reactor），坐标与版本由本目录 pom 自持：

- 坐标：`io.github.retreatisadvance.sample:consumer-sample:1.0.0-SNAPSHOT`
- 唯一版本闸：`-Dharness.version=<版本>`（缺省 `0.2.0-SNAPSHOT`，即候选腿）

## 跑法

前置：JDK 25、Maven 3.8+。

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)   # macOS；其它平台指向 JDK 25 即可
<repo>/integration/verify-consumer.sh candidate    # 候选腿 + 四负例（本机工作树装进隔离仓）
<repo>/integration/verify-consumer.sh release      # 发布腿：0.1.0 发布件（隔离仓为空、经公共 Central 代理解析）
```

脚本逐步输出 `ok/FAIL`，末行 `失败项：0` 即通过；认证/参数细节见脚本头注释。

## 目录

```
pom.xml                                  独立 POM（自有坐标 + <harness.version> 单点）
profile/consumer-sample.yml              profile：bundles: [base, consumer-sample]
src/main/java/.../WordCountPlugin.java   Plugin 实现（word_count 工具）
src/main/java/.../SelfCheck.java         治理自证主类（compose → dump → boot → 工具可见/可执行）
src/main/resources/META-INF/harness/bundle.yml   bundle 声明（行序 = 装载序）
src/main/resources/META-INF/services/...Plugin   ServiceLoader 插件声明
negatives/                               负例夹具（①②③）；④ 复用 profile + 第二参 PRODUCTION 撞 AUTO 审批
```

完整接入指南（最小 pom、classpath 与 module-path 两条消费路、组合与引用口径、排错指引）见仓根
`docs/embedding.md`。
