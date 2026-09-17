# 发布流程（0.1.0）

本仓面向发布执行者的操作手册：一次性前置（GPG / Central Portal）、发布前预检、发布命令序列、校验点与失败边界。

> **归属**：发布执行（版本翻转、签名、上传、Portal Publish、tag）为**用户侧动作**——需凭据与公开不可逆授权，agent 只维护本手册与校验点。`git push` 同样需用户放行。

## 0. 发布面（0.1.0）

- **坐标 40 个**：根 POM 1 + 聚合器 9（pom-only）+ 叶 30（jar + sources + javadoc 四件套面）
- **排除 5 个**：`harness-examples-*`（3）、`harness-dist-*`（2）——release profile `excludeArtifacts` 为实闸（`maven.deploy.skip` 仅兜标准 deploy 路径）
- groupId `io.github.retreatisadvance`（Central 命名空间 = GitHub 身份）；Java 侧包名/JPMS 名 `io.javanatic.harness.*` 不随发（不同源属有意为之，见 [02](design/02-module-layout.md)）
- 上传目标 Central Portal；`autoPublish=false`——**上传即草稿，人工审核后 Publish 才公开（不可逆）**

## 1. 一次性前置（用户执行）

### 1.1 GPG 密钥

```sh
gpg --full-generate-key                      # RSA 4096 或 ed25519；passphrase 自持
gpg --list-secret-keys --keyid-format=long   # 抄录 key id
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>   # 公钥上传（Central 校验签名）
```

签署时 gpg-agent 弹 pinentry 输入 passphrase；本机有多把私钥时以 `-Dgpg.keyname=<KEY_ID>` 指定。

### 1.2 Central Portal 账号与命名空间

1. <https://central.sonatype.com> → Sign in with GitHub
2. Namespaces：系统按 GitHub 用户名自动创建 `io.github.retreatisadvance` 并验证（无需临时仓）
3. 若 Portal 回显拼写不同（如全小写）：全仓 46 个 POM 同步校正一次即可（发布前零成本）
4. Account → Generate User Token：得到 token 用户名/密码对

### 1.3 `~/.m2/settings.xml`

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>TOKEN_USER</username>
      <password>TOKEN_PASS</password>
    </server>
  </servers>
</settings>
```

凭据只存本机 `~/.m2/settings.xml`——**永不入库**（本仓库不提交任何凭据）。

## 2. 发布前预检（本地、无副作用）

```sh
mvn -B package                                 # 全量绿（与 CI 同口径，含 spotless/checkstyle 门禁）
mvn -B -P release -Dgpg.skip=true package      # release 剖面干跑：sources/javadoc/元数据齐全
```

**注意**：`-Dgpg.skip=true` 下没有 `.asc` 是预期——干跑「无 `.asc`」**不得**当作签名达标证据（硬门槛见 §4.3）。

**另注**：Central 硬校验项——每个发布 POM 须**自带** `<name>` 元素（不继承；新增模块时勿漏）。it16 首发实撞：39 个发布包上传后被校验全数拒绝（`Project name is missing`），补 `<name>` 后重传通过。

## 3. 发布序列（0.1.0）

```sh
# 3.1 版本翻转 0.1.0-SNAPSHOT → 0.1.0（每个 POM 各一处）
mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false   # 首次运行会拉取 versions-maven-plugin
rm -rf dist/jh/target                                            # 旧版本 jar 残留 → jlink「Two versions of module」（注2）
grep -rl "0.1.0-SNAPSHOT" --include=pom.xml . | wc -l            # 翻转后应仅剩 1（孤岛，见注）；翻转前全仓 46 = reactor 45 + 孤岛 1

# 3.2 提交翻转（tag 必须指向承载该 commit 的同一提交）
git add -A && git commit -m "chore(release): 0.1.0"

# 3.3 真签名 + 上传（生成草稿，不公开）
mvn -B -P release deploy
```

> 注：`bundle/headless/` 孤岛 POM 不入 reactor，不参与 versions:set——已知状态（it16 设计偏离④），其旧版本号不影响发布面。
> 注2：版本变更后 `dist/jh/target/` 残留旧版本 jar，jlink 报 `Two versions of module ... found`；构建前 `rm -rf dist/jh/target` 即可（it16 实撞）。

## 4. 校验点

### 4.1 上传完成

- 构建日志给出 Central Portal deployment id
- 本地 bundle：**根工程 `target/central-publishing/central-bundle.zip`**
- 干跑基线：650 项 = 130 主件（30 叶 × 4 + 10 pom-only × 1）+ 520 校验和（4 种 × 130）

### 4.2 Portal 草稿审核

Portal → Publish → Deployments，核对：

- 40 坐标齐全；**examples/dist 坐标零出现**（排除面）
- 文件面：30 叶 = jar/sources/javadoc/pom 四件套 + 校验和；10 聚合器 = pom-only

### 4.3 签名硬门槛（放行必备）

- **bundle 内每主件都有 `.asc`**：真签名后 ≈ 780 项（650 + 130 个 `.asc`）
- 抽查本地工件：`gpg --verify <artifact>.jar.asc <artifact>.jar` → 「完好的签名」
- 缺 `.asc` = 未真签名（多半误带 `-Dgpg.skip=true`）——**不得**点 Publish

### 4.4 Publish（公开、不可逆）

草稿审核通过后点 Publish；Maven Central 索引随后收录（分钟到小时级）。

## 5. tag 与 GitHub Release（用户侧）

```sh
git tag v0.1.0 && git push origin v0.1.0      # push 需放行
```

GitHub Release：附归档 + notes。归档在**构建平台**产出（jlink 镜像与平台绑定）：

- `dist/jh/target/javanatic-harness-0.1.0-<平台>.tar.gz` 与同名 `.zip`（解压即用，顶层目录同名）
- 0.1.0 首发附本机（macOS/aarch64）归档；Linux 归档在 Linux 机器 `mvn -B package` 同法产出（CI 上传自动化留后续迭代）

## 6. 发布后（main 前进）

```sh
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
git add -A && git commit -m "chore: bump 0.2.0-SNAPSHOT"
```

## 7. 失败与回滚边界

- **草稿阶段失败/弃用**：Portal 删草稿即可——坐标未公开，版本号可原样重发
- **已 Publish**：不可回退，只前进——缺陷修 0.1.1，不复用坐标/版本
- **签名缺失**：见 §4.3——修复后重新 deploy（草稿可覆盖重传）

## 8. 归档产物说明（dist/jh）

- `mvn -B package` 顺带产出 tar.gz/zip（it16 S3）：`--compress=zip-6`——镜像 86M → 53M（-38%；另 `jdk.httpserver` 出链，镜像模块 41 → 40）
- 权限面：`bin/`、`lib/`（含 jspawnhelper）带执行位；`conf/`、`legal/`、`release` 常规位
- 验收口径：解压后 `<dir>/bin/jh --help` 与 `<dir>/bin/jh --verify` 均 exit 0（无 key 可跑）
