package io.javanatic.harness.workbench.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javanatic.harness.session.persistence.WriterLockException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static io.javanatic.harness.workbench.rpc.RpcFixtures.loaded;
import static io.javanatic.harness.workbench.rpc.RpcFixtures.richEvents;
import static io.javanatic.harness.workbench.rpc.RpcFixtures.summary;
import static io.javanatic.harness.workbench.rpc.RpcFixtures.tailEvents;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * handler 契约(停 1-S2 T7–T11):三方法 face、参数校验分档、域码翻译三件、
 * 游标矩阵、预算截断。handler 经 {@link JsonRpcWire} 整链断言(两层的
 * 翻译契约一起钉死);seam 为内存替身,异常按真实类型上抛。
 */
class WorkbenchRpcTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 启动期注入的组合 dump 快照(String;模块图纪律——rpc 不依赖 bundle/base)。 */
    private static final String DUMP_SNAPSHOT =
        "approval-auto\nlsp-jdtls  (disabled: true)";

    private final RpcFixtures.FakePersistence persistence = new RpcFixtures.FakePersistence();
    private final RpcFixtures.FakeTail tail = new RpcFixtures.FakeTail();

    /** handler + wire 整链:请求体 → 响应体。 */
    private String call(String body, long maxResponseBytes) {
        WorkbenchRpc rpc = new WorkbenchRpc(persistence, tail, DUMP_SNAPSHOT, maxResponseBytes);
        return new JsonRpcWire().handle(body, rpc);
    }

    private String call(String body) {
        return call(body, 8L * 1024 * 1024);
    }

    private static JsonNode parse(String body) throws IOException {
        return MAPPER.readTree(body);
    }

    private static String errorCode(String out) throws IOException {
        return String.valueOf(parse(out).get("error").get("code").asInt());
    }

    // ────────── T7 sessionsList ──────────

    @Test
    void sessionsListUsesDefaultMaxTwentyAndMapsSummaries() throws Exception {
        persistence.catalog = List.of(
            summary("s1", 2L, Optional.of("/tmp/x"), true),
            summary("s2", 1L, Optional.empty(), false));
        persistence.total = 5;

        JsonNode result = parse(call("{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionsList\",\"id\":1}"))
            .get("result");

        assertThat(persistence.lastMax).isEqualTo(20);
        assertThat(result.get("total").asInt()).isEqualTo(5);
        assertThat(result.get("sessions")).hasSize(2);
        JsonNode first = result.get("sessions").get(0);
        assertThat(first.get("id").asText()).isEqualTo("s1");
        assertThat(first.get("createdAt").asLong()).isEqualTo(1L);
        assertThat(first.get("lastActivityMillis").asLong()).isEqualTo(2L);
        assertThat(first.get("eventCount").asLong()).isEqualTo(3L);
        assertThat(first.get("cwd").asText()).isEqualTo("/tmp/x");
        assertThat(first.get("busy").asBoolean()).isTrue();
        JsonNode second = result.get("sessions").get(1);
        assertThat(second.get("cwd").isNull()).isTrue();
        assertThat(second.get("busy").asBoolean()).isFalse();
    }

    @Test
    void sessionsListAppliesExplicitMax() throws Exception {
        persistence.catalog = List.of(summary("s1", 2L, Optional.empty(), false));
        persistence.total = 1;

        parse(call("{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionsList\",\"params\":{\"max\":1},\"id\":1}"));

        assertThat(persistence.lastMax).isEqualTo(1);
    }

    @Test
    void nonPositiveMaxYieldsInvalidParams() throws Exception {
        assertThat(errorCode(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionsList\",\"params\":{\"max\":0},\"id\":1}")))
            .isEqualTo("-32602");
    }

    @Test
    void fractionalMaxYieldsInvalidParams() throws Exception {
        assertThat(errorCode(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionsList\",\"params\":{\"max\":1.5},\"id\":1}")))
            .isEqualTo("-32602");
    }

    // ────────── T8 sessionViews ──────────

    @Test
    void sessionViewsMapsFiveViewJsonShape() throws Exception {
        persistence.sessions.put("s1", loaded(richEvents()));

        JsonNode result = parse(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionViews\",\"params\":{\"session\":\"s1\"},\"id\":1}"))
            .get("result");

        assertThat(result).isEqualTo(MAPPER.readTree("""
            {"header":{"version":0,"id":"s1","createdAt":0,"parentSession":null,"seedLength":0,
             "manifest":{"rows":[{"plugin":"p","config":{"k":"v"}}]}},
             "turns":[{"turn":1,"open":false,"startTime":100,"endTime":170,"endReason":"completed",
             "steps":1,"toolCalls":1,
             "usage":{"inputTokens":10,"outputTokens":20,"reasoningTokens":0}}],
             "tools":[{"turn":1,"step":1,
             "pairs":[{"callSeq":2,"name":"fs/read","arguments":"{\\"path\\":\\"x\\"}",
             "resultSeq":3,"isError":false,"concludesTurn":false,"durationMillis":10}],
             "orphans":[]}],
             "usage":{"total":{"inputTokens":10,"outputTokens":20,"reasoningTokens":0},
             "perTurn":[{"turn":1,"usage":{"inputTokens":10,"outputTokens":20,"reasoningTokens":0}}]},
             "timeline":[
             {"seq":0,"time":100,"type":"turn/start","recognized":true,
              "detail":{"kind":"turn","turn":1,"reason":null}},
             {"seq":1,"time":110,"type":"step/start","recognized":true,
              "detail":{"kind":"step","turn":1,"step":1}},
             {"seq":2,"time":120,"type":"tool/call","recognized":true,
              "detail":{"kind":"toolCall","turn":1,"step":1,"name":"fs/read","arguments":"{\\"path\\":\\"x\\"}"}},
             {"seq":3,"time":130,"type":"tool/result","recognized":true,
              "detail":{"kind":"toolResult","turn":1,"step":1,"isError":false,"contentChars":7,"concludesTurn":false}},
             {"seq":4,"time":140,"type":"user/message","recognized":true,
              "detail":{"kind":"user","source":"Tool","contentChars":0}},
             {"seq":5,"time":150,"type":"assistant/message","recognized":true,
              "detail":{"kind":"assistant","turn":1,"step":1,"contentChars":0,
              "usage":{"inputTokens":10,"outputTokens":20,"reasoningTokens":0}}},
             {"seq":6,"time":160,"type":"llm/request","recognized":true,
              "detail":{"kind":"llmRequest","turn":1,"step":1,"from":4,"to":5,"params":{"model":"m"}}},
             {"seq":7,"time":170,"type":"turn/end","recognized":true,
              "detail":{"kind":"turn","turn":1,"reason":"completed"}},
             {"seq":8,"time":180,"type":"session/end-seed","recognized":true,"detail":null}],
             "messages":[
             {"kind":"user","source":{"kind":"tool","toolUseId":"c1"},
              "blocks":[{"type":"toolResult","toolUseId":"c1","content":"content","isError":false}]},
             {"kind":"user","source":{"kind":"tool","toolUseId":"c1"},
              "blocks":[{"type":"toolResult","toolUseId":"c1","content":"content","isError":false}]},
             {"kind":"assistant","source":{"kind":"model","provider":"p","model":"m"},
              "blocks":[{"type":"toolUse","id":"c1","name":"fs/read","arguments":"{}"}]}]}
            """));
    }

    @Test
    void sessionViewsMissingSessionParamYieldsInvalidParams() throws Exception {
        assertThat(errorCode(call("{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionViews\",\"id\":1}")))
            .isEqualTo("-32602");
    }

    @Test
    void unknownSessionYieldsSessionNotFound() throws Exception {
        assertThat(errorCode(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionViews\",\"params\":{\"session\":\"nope\"},\"id\":1}")))
            .isEqualTo("-32001");
    }

    @Test
    void lockedSessionYieldsSessionLocked() throws Exception {
        persistence.ioFailure = new WriterLockException("writer lock held");
        assertThat(errorCode(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionViews\",\"params\":{\"session\":\"s1\"},\"id\":1}")))
            .isEqualTo("-32002");
    }

    @Test
    void corruptLogYieldsLogCorrupt() throws Exception {
        persistence.runtimeFailure = new IllegalStateException("seq gap at 3");
        assertThat(errorCode(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionViews\",\"params\":{\"session\":\"s1\"},\"id\":1}")))
            .isEqualTo("-32003");
    }

    @Test
    void unexpectedIoYieldsInternalError() throws Exception {
        persistence.ioFailure = new IOException("disk gone");
        assertThat(errorCode(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionViews\",\"params\":{\"session\":\"s1\"},\"id\":1}")))
            .isEqualTo("-32603");
    }

    // ────────── T9 sessionTail 游标矩阵 ──────────

    @Test
    void tailFromHeadDeliversAllEventsWithSeqCursor() throws Exception {
        tail.sessions.put("s1", tailEvents());

        JsonNode result = parse(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionTail\",\"params\":{\"session\":\"s1\",\"lastSeq\":-1},\"id\":1}"))
            .get("result");

        assertThat(result.get("nextSeq").asInt()).isEqualTo(2);
        assertThat(result.get("events")).hasSize(3);
        assertThat(result.get("events").get(0).get("seq").asInt()).isEqualTo(0);
        assertThat(result.get("events").get(2).get("type").asText()).isEqualTo("session/end-seed");
        assertThat(tail.closed).isTrue();
    }

    @Test
    void tailFromCursorDeliversIncrementOnly() throws Exception {
        tail.sessions.put("s1", tailEvents());

        JsonNode result = parse(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionTail\",\"params\":{\"session\":\"s1\",\"lastSeq\":0},\"id\":1}"))
            .get("result");

        assertThat(result.get("nextSeq").asInt()).isEqualTo(2);
        assertThat(result.get("events")).hasSize(2);
        assertThat(result.get("events").get(0).get("seq").asInt()).isEqualTo(1);
    }

    @Test
    void tailAtTailReturnsEmptyIncrement() throws Exception {
        tail.sessions.put("s1", tailEvents());

        JsonNode result = parse(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionTail\",\"params\":{\"session\":\"s1\",\"lastSeq\":2},\"id\":1}"))
            .get("result");

        assertThat(result.get("nextSeq").asInt()).isEqualTo(2);
        assertThat(result.get("events")).isEmpty();
    }

    @Test
    void tailAheadOfLogYieldsInvalidParams() throws Exception {
        tail.sessions.put("s1", tailEvents());

        assertThat(errorCode(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionTail\",\"params\":{\"session\":\"s1\",\"lastSeq\":3},\"id\":1}")))
            .isEqualTo("-32602");
    }

    @Test
    void tailNegativeBelowMinusOneYieldsInvalidParams() throws Exception {
        tail.sessions.put("s1", tailEvents());

        assertThat(errorCode(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionTail\",\"params\":{\"session\":\"s1\",\"lastSeq\":-2},\"id\":1}")))
            .isEqualTo("-32602");
    }

    @Test
    void tailMissingLastSeqYieldsInvalidParams() throws Exception {
        tail.sessions.put("s1", tailEvents());

        assertThat(errorCode(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionTail\",\"params\":{\"session\":\"s1\"},\"id\":1}")))
            .isEqualTo("-32602");
    }

    @Test
    void emptySessionTailFromHeadYieldsMinusOneCursor() throws Exception {
        tail.sessions.put("s1", List.of());

        JsonNode result = parse(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionTail\",\"params\":{\"session\":\"s1\",\"lastSeq\":-1},\"id\":1}"))
            .get("result");

        assertThat(result.get("nextSeq").asInt()).isEqualTo(-1);
        assertThat(result.get("events")).isEmpty();
    }

    @Test
    void tailUnknownSessionYieldsSessionNotFound() throws Exception {
        assertThat(errorCode(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionTail\",\"params\":{\"session\":\"nope\",\"lastSeq\":-1},\"id\":1}")))
            .isEqualTo("-32001");
    }

    // ────────── T10 撕裂尾容忍(handler 侧) ──────────

    @Test
    void tailDrainStopsAtEmptyBatchWithoutError() throws Exception {
        tail.sessions.put("s1", tailEvents());

        JsonNode result = parse(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionTail\",\"params\":{\"session\":\"s1\",\"lastSeq\":-1},\"id\":1}"))
            .get("result");

        // seam 不交付末行不完整批(撕裂尾);handler 只消费完整批、空批终止排空
        assertThat(result.get("events")).hasSize(3);
        assertThat(result.get("nextSeq").asInt()).isEqualTo(2);
    }

    @Test
    void tailDrainAccumulatesAcrossBatches() throws Exception {
        tail.batchSize = 2;
        tail.sessions.put("s1", tailEvents());

        JsonNode result = parse(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionTail\",\"params\":{\"session\":\"s1\",\"lastSeq\":-1},\"id\":1}"))
            .get("result");

        // 真实 seam 分批发批:排空循环跨批累积,空批终止
        assertThat(result.get("events")).hasSize(3);
        assertThat(result.get("events").get(2).get("type").asText()).isEqualTo("session/end-seed");
        assertThat(result.get("nextSeq").asInt()).isEqualTo(2);
    }

    // ────────── T11 响应预算 ──────────

    @Test
    void oversizedResponseYieldsResponseTooLarge() throws Exception {
        persistence.sessions.put("s1", loaded(richEvents()));

        assertThat(errorCode(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionViews\",\"params\":{\"session\":\"s1\"},\"id\":1}",
            64)))
            .isEqualTo("-32004");
    }

    @Test
    void budgetWithinLimitPasses() throws Exception {
        persistence.sessions.put("s1", loaded(richEvents()));

        JsonNode response = parse(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionViews\",\"params\":{\"session\":\"s1\"},\"id\":1}",
            64 * 1024));

        assertThat(response.get("result")).isNotNull();
        assertThat(response.get("error")).isNull();
    }

    // ────────── it36 停 1 compositionDump 读面(注入快照) ──────────

    @Test
    void compositionDumpReturnsInjectedSnapshot() throws Exception {
        JsonNode result = parse(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/compositionDump\",\"id\":1}"))
            .get("result");

        assertThat(result.get("dump").asText()).isEqualTo(DUMP_SNAPSHOT);
    }

    @Test
    void compositionDumpIgnoresParams() throws Exception {
        JsonNode result = parse(call(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/compositionDump\","
                + "\"params\":{\"session\":\"s1\"},\"id\":1}"))
            .get("result");

        assertThat(result.get("dump").asText()).isEqualTo(DUMP_SNAPSHOT);
    }

    @Test
    void compositionDumpOversizedYieldsResponseTooLarge() throws Exception {
        WorkbenchRpc rpc = new WorkbenchRpc(persistence, tail, "x".repeat(128), 64);
        String out = new JsonRpcWire().handle(
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/compositionDump\",\"id\":1}", rpc);

        assertThat(errorCode(out)).isEqualTo("-32004");
    }

    // ────────── -32601 method not found(handler 是唯一生产者) ──────────

    @Test
    void unknownMethodYieldsMethodNotFound() throws Exception {
        assertThat(errorCode(call("{\"jsonrpc\":\"2.0\",\"method\":\"workbench/nope\",\"id\":1}")))
            .isEqualTo("-32601");
    }
}
