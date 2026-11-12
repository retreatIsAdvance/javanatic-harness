# it34 停 3 门禁面 —— 全反应器测试 + CI 回填位

## 本地全反应器（2026-11-12 16:43–16:46:49）

```
mvn test
[INFO] BUILD SUCCESS
[INFO] Total time:  03:39 min
```

- 新鲜 surefire XML **179 件 = 1403 tests / 0 failures / 0 errors / 31 skipped**（按 XML `tests/failures/errors/skipped` 属性聚合）。
- 与停 2 全量（1399）对账：+3 = 停 2 修订 F2 的 TermIoTest 新增三测（修订批只跑了聚焦,首次进全量）；+1 = 停 3 关闭钩子测试。**1403 = 1399 + 3 + 1,逐项对上。**
- 已知陈旧件隔离：`AskVisibilityProbe.xml`（**Nov 6** 16:52 失败报告,探针类不随全量重跑,停 2 已知项原样）——今日截止线聚合已排除,非本批失败。

## CI 四腿（收口批 push 后回填）

- 待推批次：停 2 四笔（`9671878`/`c2f33fa`/`3991a1f`/`d499051`）+ 修订 `86ad9e5` + 停 3 两笔（关闭钩子 + 取证/同步面）。
- [ ] CI run `<id>`：build / macos / windows / windows-arm 四腿 + `.bat` 烧入行取证（archive-windows profile 首次生效面）。
