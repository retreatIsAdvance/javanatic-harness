# it38 停 3 用户检视 runbook（三栏新壳；serve 由代理起，浏览器由你亲手）

> 旧版 [it36 停 3 runbook](../iteration-36/stop3-user-runbook.md) 的 DOM 脚本锚在旧壳（header + 顶部两页签），**不要再用**——it38 已改为 dsh 式三栏壳（左会话栏 / 中主区 / 右审批与上下文）。本文件的脚本与断言面是新壳唯一口径。

前提：镜像已重建（`dist/jh/target/jlink-image/bin/jh`）。serve 由代理在后台起（端口 8787，工作区 `~/study/deepseek/jh-web-acceptance`），你只需打开浏览器。

## 第 1 步：打开页面

```
http://127.0.0.1:8787/
```

预期首屏：左侧品牌 + 会话清单 + 三向导航（会话观察 / 组合编辑器 / 聊天），中间「会话观察」，右侧「审批 + 当前会话」栏。浅色为主，跟随系统自动深色。

## 第 2 步：新壳 DOM 断言（DevTools Console 粘贴，只读零副作用）

```
(() => {
  const checks = [];
  const ok = (name, cond) => checks.push((cond ? "PASS " : "FAIL ") + name);
  ok("标题", document.title === "Javanatic Workbench");
  ok("三栏壳", ["app-shell","sidebar","main","rightbar"].every(id => document.getElementById(id)));
  ok("左栏三向导航", ["tab-sessions","tab-composition","tab-chat"].every(id => document.getElementById(id)));
  ok("会话清单非空", document.querySelectorAll("#session-list li").length > 0);
  ok("五视图按钮", ["turns","tools","usage","timeline","messages"].every(v => document.getElementById("view-" + v)));
  ok("编辑器骨架", document.getElementById("composition-editor").value.startsWith("name: my-profile"));
  ok("编辑器三按钮", ["load-dump","verify-composition","download-profile"].every(id => document.getElementById(id)));
  ok("错误条隐藏", document.getElementById("error-bar").style.display !== "block");
  ok("审批卡初始隐藏", document.getElementById("approval-overlay").style.display === "none");
  ok("聊天壳就位", ["chat-log","chat-input","chat-send","chat-new","chat-sessions","chat-attach"].every(id => document.getElementById(id)));
  ok("右栏上下文", !!document.getElementById("chat-state") && !!document.getElementById("chat-session"));
  const shell = getComputedStyle(document.getElementById("app-shell"));
  ok("三栏栅格生效", shell.display === "grid" && shell.gridTemplateColumns.split(" ").length === 3);
  return checks.join("\n");
})()
```

预期 12 行全 PASS。窗口宽度 < 1180px 时三栏右栏会变为审批浮卡、末行 FAIL 属预期，请把窗口拉到 ≥ 1200px 再跑。

## 第 3 步：视觉检视（请截图回贴）

1. **浅色三栏**：整页截图（左/中/右三栏同时可见）。
2. **深色三栏**：DevTools → Rendering → `prefers-color-scheme: dark`（或系统切深色）后整页截图。
3. 点左栏任一**会话** → 中区进「会话观察」，五视图逐个切换，确认表格/时间线可读。
4. 点「组合编辑器」→ 点「载入当前组合 dump（对照）」→ 灰底投影出现；把 `bundles: [base]` 改坏再「保存前校验」应见红框，改回再校验应绿框。
5. 点「聊天」→ 观察右栏「当前会话」上下文卡（状态徽标、会话行、取消按钮）。

## 第 4 步：聊天 + 审批实况（写面）

1. 在输入框输入一条会触发工具的指令（如「列一下当前目录」），点「新会话」。
2. 预期：用户气泡右对齐、助手回复为正文流、工具行等宽小卡；右栏状态从「运行中」到「等待审批」。
3. 审批出现时：右栏审批卡展开（琥珀色提示条 + 等宽命令摘要 + 允许/拒绝按钮），输入区隐藏；按 Enter 允许 / Escape 拒绝 / 点按钮均可结算。
4. 审批结算后输入区恢复；如模型提问，日志出现琥珀「模型提问」卡，状态「等待答复」，输入后发送（即 resume）。
5. 深色模式下再触发一次审批，确认审批卡与按钮对比度可读。

## 第 5 步：SIGINT 收口（由代理执行，记录供你核对）

代理在 serve 终端按 Ctrl-C 一次：exit 0、`lsof -nP -iTCP:8787 -sTCP:LISTEN` 零输出。

## 回贴清单

- 第 2 步 12 行断言原文（任一 FAIL 原样贴）
- 浅色 + 深色整页截图各一张；审批卡实况一张（浅/深其一）
- 第 4 步状态迁移与气泡观感一句话
- 任何错框/异常原文——错误也是证据
