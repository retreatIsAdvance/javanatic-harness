/**
 * harness-workbench-rpc — 0.5 工作台 W3:本地回环 JSON-RPC server。
 * 纯消费 W1 投影(五视图)+ W2 tail seam(SessionTail 四契约)+ 会话清单
 * (SessionPersistence.Catalog);方法面 v1 三件 = sessionsList /
 * sessionViews / sessionTail。JSON-RPC 2.0 请求-响应 only(v1 无 batch /
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
