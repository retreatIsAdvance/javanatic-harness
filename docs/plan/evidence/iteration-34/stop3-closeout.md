# it34 停 3 门禁面 —— 全反应器测试 + CI 回填位

## 本地全反应器（2026-11-12 16:43–16:46:49）

```
mvn test
[INFO] BUILD SUCCESS
[INFO] Total time:  03:39 min
```

- 新鲜 surefire XML **179 件 = 1403 tests / 0 failures / 0 errors / 31 skipped**（按 XML `tests/failures/errors/skipped` 属性聚合）。
- 与停 2 全量（1399）对账：+3 = 停 2 修订 F2 的 TermIoTest 新增三测（修订批只跑了聚焦,首次进全量）；+1 = 停 3 关闭钩子测试。**1403 = 1399 + 3 + 1,逐项对上。**
- 已知陈旧件隔离：`AskVisibilityProbe.xml`（**Nov 6** 16:52 失败报告,探针类不随全量重跑,停 2 已知项原样）+ `IZProbeTest.xml`（**Oct 13**,同类陈旧,2 tests）——今日截止线聚合已排除,非本批失败。

## CI 四腿（2026-11-13 回填,run `43355344606`）

- 收口批实际 push **12 笔**（`c2f3b5d..19a5cdc`）：停 1 两笔（`9b6cf22`/`de828ea`）+ 停 2 四笔（`9671878`/`c2f33fa`/`3991a1f`/`d499051`）+ 停 2 修订 `86ad9e5` + 停 3 两笔（`ef965e4`/`6f82cb0`）+ 停 3 修订批 `11d36a0` + 收口批两笔（`76384b1` 复审补档 / `19a5cdc` 复审归档+收口）——headSha `19a5cdc` 与 CI run 一致。
- CI run `43355344606`（push `19a5cdc` 触发）：**conclusion=success**。四腿全绿——build（linux, 03:02:15Z）/ macos（03:01:29Z）/ windows（03:03:08Z）/ windows-arm（03:04:21Z）；release-attach skipped（非 tag push,预期）。
- `.bat` 烧入取证（windows 双腿,archive-windows profile 首次生效面）：
  - antrun `burn-native-access-into-launcher-bat` 步两腿均执行（win log 5751 / win-arm log 5800）。
  - 镜像冒烟 + 归档解包冒烟四面：`jh.bat --help` 含 `--workbench` 行、`jh.bat --verify` 计数 `composition: 34 rows, 41 discovered, 0 unreferenced`（win 6020/6146、win-arm 6069/6195）、windows-acl 强制行断言通过、LASTEXITCODE 闸全过。
  - **运行期 WARNING 面 = 零**：F1 签名（`A restricted method in java.lang.System` / `System::load`）双腿 0 命中；全腿 857 处 WARNING 均为 Build and test 步 javac `[WARNING]` 编译面（既有）+ spotless Unsafe 告警,与烧入无关。
