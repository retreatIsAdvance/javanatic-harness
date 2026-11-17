/**
 * harness-workbench-rpc — 0.5 工作台 W3:本地回环 JSON-RPC server。
 * 纯消费 W1 投影(五视图)+ W2 tail seam(SessionTail 四契约)+ 会话清单
 * (SessionPersistence.Catalog);方法面 v1 五件 = sessionsList /
 * sessionViews / sessionTail / compositionDump / verifyComposition
 * (it36 W4 增:dump 启动期 String 快照注入、verify 经 CompositionVerifier
 * 函数接口注入——本模块不依赖 bundle/base)。W4 静态服务面:仅精确
 * "/" 的 GET/HEAD 服务单文件 ui.html(WorkbenchUi classpath 加载,
 * 资源随模块 jar 进镜像)。JSON-RPC 2.0 请求-响应 only(v1 无 batch /
 * 通知);jdk.httpserver 恒绑 127.0.0.1 回环(B29 硬线);零 Plugin、零新
 * 配置键(headless 直依赖接线,it34 tui 先例);Jackson 仅此边界。
 */
module io.javanatic.harness.workbench.rpc {
    requires io.javanatic.harness.workbench.projection;
    requires io.javanatic.harness.session.persistence;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.kernel.config;
    requires com.fasterxml.jackson.databind;
    requires jdk.httpserver;
    requires static java.net.http; // 测试:绑定面真 HTTP 往返客户端(static=不进 jlink 镜像)

    exports io.javanatic.harness.workbench.rpc;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.workbench.rpc;
}
