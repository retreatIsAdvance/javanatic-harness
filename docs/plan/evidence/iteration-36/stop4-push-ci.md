# it36 停 3-S4：push 批 + CI 四腿 + run 号回填（2026-11-17）

授权：用户 it36 收口终审消息「通过——授权 push，0.5 四站全通」（2026-11-17）。

## 1. push 批（10 笔，5e37062..69a9a68）

批内容 = it36 全迭代提交（首笔 dc52aa7 四确认讨论稿 … 末笔 69a9a68 收口批）；远端基线 = it35 关账回填 tip `5e37062`。

```sh
git rev-list --count 5e37062..69a9a68
# 10
```

### push 波折如实记档（三次失败尝试 + 第四次成功）

GitHub 直连 flaky（既有观察账），本批实撞四回合：

| 回合 | 方式 | 结果 | 判定证据 |
|---|---|---|---|
| 1 | 钉 140.82.113.3（探活后起推，pre-push 全量门禁随跑） | **假成功**：任务 exit 0，但日志尾 `错误：RPC 失败。curl 16 Send failure: Broken pipe` / `send-pack: unexpected disconnect` / `致命错误：远端意外挂断了` + 一行 `Everything up-to-date` 伪影 | gh api 实核远端仍在 5e37062；本地 ahead 10 未动 |
| 2 | 重探四 IP（113.3/116.3/112.3 活）后钉 116.3 重试 | 连接超时 `PUSH_EXIT=128`（日志仅 2 行——**门禁未跑**，失败在初始连接；探路活 IP 在几分钟内死掉） | gh api 实核远端未动 |
| 3 | 逗号多 IP 列表 `http.curloptResolve=A,B,C` | git 拒：`Couldn't parse CURLOPT_RESOLVE entry`（PUSH_EXIT=128，**未发生任何写**） | 同上 |
| 4 | **重复 `-c` 双活 IP 钉扎**（113.3 + 116.3；先 ls-remote 验证 git 层连通） | **成功** | 见下 |

教训入档：①后台 push 的 exit 0 不可信，一律以 **gh api 远端 ref 实核**为准；②探路→推送时间窗要压缩（IP 面分钟级换血）；③多 IP 钉扎用重复 `-c http.curloptResolve=` 条目，逗号列表这版 git 不接受。

### 成功回合关键输出（/tmp/it36-s4-push-retry3.log，1885 行含 pre-push 全量门禁）

```text
To https://github.com/retreatIsAdvance/javanatic-harness.git
   5e37062..69a9a68  master -> master
PUSH_EXIT=0
```

```sh
gh api repos/retreatisadvance/javanatic-harness/git/ref/heads/master --jq '.object.sha'
# 69a9a687b511c60b79358f9adb07381fc4814118   ← 远端实核 = 本地 tip
git status -sb | head -1
# ## master...origin/master   ← 同步，无 ahead
```

## 2. CI 四腿全绿（run 44043766139）

```sh
gh run list --repo retreatIsAdvance/javanatic-harness --branch master --limit 3 --json databaseId,headSha,status,conclusion
# databaseId=44043766139, headSha=69a9a687…, status=in_progress（触发确认）
gh run watch 44043766139 --repo retreatIsAdvance/javanatic-harness --exit-status
# CI_WATCH_EXIT=0
gh run view 44043766139 --repo retreatIsAdvance/javanatic-harness --json conclusion,status,jobs
```

jobs 实测（startedAt → completedAt，UTC）：

| 腿 | conclusion | 时长 |
|---|---|---|
| build | success | 6m11s（08:05:27→08:11:38） |
| macos | success | 6m18s（08:05:28→08:11:46） |
| windows | success | 9m19s（08:05:29→08:14:48） |
| windows-arm | success | 9m35s（08:05:28→08:15:03） |
| release-attach | skipped | 非 tag 常态 |

run 级 conclusion=success / status=completed。**Windows 双腿首跑即绿**——用户预警的 `--serve`/UI 资源路径平台特异风险未发生（纯 Java + classpath 资源面，无平台特异 API，与侦察预期一致）。macos 腿 TwoHostLostProbe flake（it34 观察账）本 run 未再现。CI 公告噪声（Node.js 20 deprecation / setup-java v4 deprecation / macOS arm64 排队提示）为 runner 面通告，非本仓缺陷，不入验收。

## 3. run 号回填面（随本批提交）

- iteration-36.md：标题 + 停 3 行 + 验收 CI 项勾选（run 号 + 四腿时长）。
- current-state.md：it36 条目尾句翻终态（CI 全绿 + push 批落地 + 无待推）。
- AGENTS.md：it36 段尾句翻终态。
- 本证据档（stop4-push-ci.md）新建入仓。

it36 关账终态 = 验收清单全勾。W5 预登记 + dsh 三面侦察已另批提交（656f60d，不 push 待点名）；0.5.0 发布另议。