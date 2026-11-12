/**
 * harness-workbench-tui — 0.5 工作台 W2:全屏 TUI 工作台。
 * 纯消费 W1 投影(workbench/projection 五视图)+ seam 流式读面;渲染器
 * 纯函数可测,端子 IO 薄壳最小化。检视态复用五视图投影渲染;跟随态
 * (--follow)对流式读面轮询、每 poll 全量重投影 live tail。
 */
module io.javanatic.harness.workbench.tui {
    requires io.javanatic.harness.workbench.projection;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.kernel.brand;

    exports io.javanatic.harness.workbench.tui;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.workbench.tui;
}
