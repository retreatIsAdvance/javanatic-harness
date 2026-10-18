package io.javanatic.harness.mcp.tool;

import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.mcp.McpCallResult;
import io.javanatic.harness.mcp.McpClientService;
import io.javanatic.harness.mcp.McpServerConnection;
import io.javanatic.harness.mcp.McpServerState;
import io.javanatic.harness.mcp.McpToolDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** mcp/tool 测试桩：可变连接 / 可变客户端 / JUL 捕获器（System.Logger 的 JUL 后端）。 */
final class Fakes {

    private Fakes() {
    }

    static McpToolDescriptor tool(String serverId, String name, String description, String schemaJson) {
        return new McpToolDescriptor(serverId, name, description, schemaJson);
    }

    /** 连接桩：tools 冻结、state 可变、调用结果可编程且留痕。 */
    static final class FakeConnection implements McpServerConnection {

        private final String serverId;
        private final List<McpToolDescriptor> tools;
        private McpServerState state = McpServerState.CONNECTED;
        private long catalogGeneration = 1;
        private McpCallResult next = McpCallResult.success("ok");
        private String lastToolName;
        private String lastArgumentsJson;

        FakeConnection(String serverId, McpToolDescriptor... tools) {
            this.serverId = serverId;
            this.tools = List.of(tools);
        }

        void state(McpServerState state) {
            this.state = state;
        }

        void next(McpCallResult next) {
            this.next = next;
        }

        String lastToolName() {
            return lastToolName;
        }

        String lastArgumentsJson() {
            return lastArgumentsJson;
        }

        @Override
        public String serverId() {
            return serverId;
        }

        @Override
        public McpServerState state() {
            return state;
        }

        @Override
        public List<McpToolDescriptor> tools() {
            return tools;
        }

        @Override
        public long catalogGeneration() {
            return catalogGeneration;
        }

        @Override
        public McpCallResult call(String toolName, String argumentsJson, AbortSignal signal) {
            lastToolName = toolName;
            lastArgumentsJson = argumentsJson;
            return next;
        }
    }

    /** 客户端桩：连接列表可变（模拟配置/清单会话间变化），注册面与真注册表同语义。 */
    static final class FakeClient implements McpClientService {

        private final List<McpServerConnection> connections = new CopyOnWriteArrayList<>();

        FakeClient(McpServerConnection... connections) {
            this.connections.addAll(List.of(connections));
        }

        void connections(List<McpServerConnection> connections) {
            this.connections.clear();
            this.connections.addAll(connections);
        }

        @Override
        public List<McpServerConnection> connections() {
            List<McpServerConnection> ordered = new ArrayList<>(connections);
            ordered.sort(Comparator.comparing(McpServerConnection::serverId));
            return List.copyOf(ordered);
        }

        @Override
        public Optional<McpServerConnection> connection(String serverId) {
            return connections.stream()
                .filter(connection -> connection.serverId().equals(serverId))
                .findFirst();
        }

        @Override
        public Disposable register(McpServerConnection connection) {
            if (connection(connection.serverId()).isPresent()) {
                throw new IllegalStateException(
                    "mcp server already registered: '" + connection.serverId() + "'");
            }
            connections.add(connection);
            return Disposable.of(() -> connections.remove(connection));
        }
    }

    /** JUL 捕获器：挂在插件日志树上，收集全部记录（测试按级别/内容过滤）。 */
    static final class LogCapture extends Handler implements AutoCloseable {

        private final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final Logger logger;

        LogCapture(String loggerName) {
            this.logger = Logger.getLogger(loggerName);
            logger.setUseParentHandlers(false);
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
            logger.setUseParentHandlers(true);
        }

        /** WARN 及以上文案（映射告警断言面）。 */
        List<String> warnings() {
            return records.stream()
                .filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
                .map(LogRecord::getMessage)
                .toList();
        }
    }
}
