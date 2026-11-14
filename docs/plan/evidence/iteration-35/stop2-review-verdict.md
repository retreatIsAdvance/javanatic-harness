# it35 停 2 裁决归档（2026-11-14，用户侧评审原文）

**结论：放行 → 停 3**。packet 为 [stop2-review-packet.md](stop2-review-packet.md)（提交 `bb711c7` 同批入仓）。

（以下为评审原文摘录，冻结不改写。）

## 用户复核面

核验已完成：headless 121 + persistence-jsonl 47 独立复绿、`HeadlessServeTest` 报告在场、validate EXIT=0、树净、B29 转正 + B35 入表、dispatch 先于 key 检查（e2e 无 key 跑通 = 行为学证明）。

**三段排障纪律维持点名**：① 「报告缺席」先只读取证再 jstack 拿铁证（假设被证据杀死）；② 夹具缺陷与产品缺陷切分干净（BSD socket 语义是夹具的锅，产品零改动）；③ 突变未红当发现（影子拦截覆盖缺口 → 派生断言 TDD 补钉）。surefire `-Dtest=A+B` 假绿入 pits。

## 停 3 必验清单（停 1 附条件五项 + 用户增补一项）

1. jlink `--serve` 三方法 curl；
2. **真 jsonl 后端三方法 curl**（停 1 硬条件——放行附条件升格项）；
3. B29 非回环拒绝实录；
4. CI 四腿；
5. 收口批（verify-stop full 层 + consumer 腿新鲜跑——1448 测试规模下计数面会涨）；
6. **用户检视实录**：jlink 起 serve → curl 三方法 → SIGINT 收口，由用户执行、实录交回入档（it29–it31 分工）——serve 起来后通知用户即可。

## 裁决

**放行停 2，进停 3（真机验收 + 收口）**：按上表六项执行；CI 四腿在 push 授权后核验；收口批落完即 it35 关账。
