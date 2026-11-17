# it36 停 3 用户检视 runbook（serve 由你亲手起）

前提：新鲜镜像已构建（S1，IMAGE_BUILD_EXIT=0）。全程约 10 分钟。实录（终端输出 + 浏览器观察 + 断言结果）回贴后归档 stop3-user-round.txt。

## 第 1 步：起 serve（你的终端 A，交互终端——SIGINT 天然可用）

```
cd /Users/abel_yang/study/deepseek/javanatic-harness
dist/jh/target/jlink-image/bin/jh --serve
```

预期 stdout 两行：

```
JSON-RPC server 127.0.0.1:8787（只读工作台面；Ctrl-C 退出）
UI: http://127.0.0.1:8787/（浏览器打开）
```

终端 A 保持开着（serve 前台跑）。无需 API key（--serve 是 keyless 只读旁路）。

## 第 2 步：真浏览器面 + DOM 断言

浏览器打开 `http://127.0.0.1:8787/`。预期：标题 Javanatic Workbench、两页签（会话/组合编辑器）、会话清单**自动加载**（你的 ~/.harness/sessions 有 82 个会话，应见列表非空）。

DevTools Console 粘贴以下断言脚本（我提供；只读 DOM，零副作用），回车后把输出全贴回：

```
(() => {
  const checks = [];
  const ok = (name, cond) => checks.push((cond ? "PASS " : "FAIL ") + name);
  ok("标题", document.title === "Javanatic Workbench");
  ok("两页签", !!document.getElementById("tab-sessions") && !!document.getElementById("tab-composition"));
  ok("会话清单非空", document.querySelectorAll("#session-list li").length > 0);
  ok("五视图按钮", ["turns","tools","usage","timeline","messages"].every(v => document.getElementById("view-" + v)));
  ok("编辑器骨架", document.getElementById("composition-editor").value.startsWith("name: my-profile"));
  ok("编辑器三按钮", ["load-dump","verify-composition","download-profile"].every(id => document.getElementById(id)));
  ok("错误条隐藏", document.getElementById("error-bar").style.display !== "block");
  return checks.join("\n");
})()
```

预期 7 行全 PASS。

手动观察（逐项记一句实况）：

1. 点一个会话（建议 `headless-1789702472876-c2dd`，万条事件大会话）→ 详情面板出现，header 行含会话 id/创建时间/版本/seed。
2. 五视图子页签逐个切换：轮次/工具/用量/时间线/消息——各有表格或「（空）」，时间线应见大量事件行。
3. 勾「tail 跟随」→ 无错误条即正常（空闲会话无写者，2s 轮询返回零增量、不添行是预期；错误即停并红条才是缺陷）。取消勾选。
4. 切回「会话」页签再进「组合编辑器」——tail 应已自动停（切页签停 tail 是契约）。

## 第 3 步：编辑器干跑闭环（改坏→违规→修好→通过→下载）

1. 点「载入当前组合 dump（对照）」→ 下方灰底 pre 显示 48 行组合投影（含 `(disabled: …)` 注解与 `****` 脱敏）。
2. **改坏**：把编辑器里 `bundles: [base]` 改成 `bundles: [no-such-bundle]` → 点「保存前校验」→ 预期红框：`校验未通过（1 项违规）：1. 组合错误: IllegalStateException: profile references unknown bundle: no-such-bundle`。
3. **修好**：改回 `bundles: [base]` → 再点「保存前校验」→ 预期绿框：`校验通过——可下载后手动保存到 ~/.harness/profiles/<name>/profile.yml`。
4. **下载**：点「下载 profile.yml」→ 浏览器落盘（不经 server）。终端 B 核对内容：

```
cat ~/Downloads/profile.yml
```

预期恰为编辑器内容（name: my-profile / policy: standard / bundles: [base] / rows: []；若同名文件已存在，浏览器可能落 `profile (1).yml`，按实际文件名 cat）。

5. **零写面旁证**（干跑不触碰已落盘 profile、临时目录即清）：

```
ls -lt ~/.harness/profiles | head -5
find "${TMPDIR:-/tmp}" -maxdepth 1 -name 'jh-verify-composition*'
```

预期：profiles 最新 mtime 仍是旧日期（无新条目）；find 零输出。

## 第 4 步：SIGINT 收口（终端 A）

在 serve 终端按 **Ctrl-C 一次**，随即：

```
echo $?
lsof -nP -iTCP:8787 -sTCP:LISTEN
```

预期：`echo $?` = **0**（首按干净关停）；lsof **零输出**（端口释放）。

备注（如实）：再按 halt(130) 逃生门只在首按 close 未收敛时可达——实测 close 0.02s 收敛，双击按不出来；该分支由 ServeShutdown 单测同步驱动钉死，真机不强求。

## 回贴清单

- 第 1 步 banner 两行原文
- 第 2 步断言脚本 7 行输出 + 手动观察 4 项实况
- 第 3 步红框/绿框原文 + cat 下载文件输出 + 零写面两条输出
- 第 4 步 echo $? 与 lsof 输出

任何 FAIL/异常原样贴回——错误也是证据（it35 先例：参数猜错恰成错误翻译层活体证明）。