package io.javanatic.harness.workbench.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 写面 seam 契约(it38 停 1,T1 精度):rpc 在有/无 {@link ChatControl} 两种装配下
 * 可独立测试——不依赖 headless。覆盖四方法派发、参数校验、域错误透传、
 * 写面关闭 -32601(0.5.0 行为不变)。
 */
class WorkbenchRpcWriteTest {

    private final RpcFixtures.FakePersistence persistence = new RpcFixtures.FakePersistence();
    private final RpcFixtures.FakeTail tail = new RpcFixtures.FakeTail();
    private final ObjectMapper mapper = new ObjectMapper();
    private final FakeChat chat = new FakeChat();

    @Test
    void startDelegatesAndReturnsSessionId() throws Exception {
        JsonNode result = rpc(chat).call(WorkbenchRpc.METHOD_SESSION_START, params("""
            {"text":"hello"}
            """));

        assertThat(result.get("sessionId").asText()).isEqualTo("sess-new");
        assertThat(chat.startedText).isEqualTo("hello");
    }

    @Test
    void sendDelegatesAndTurnInFlightRejected() throws Exception {
        WorkbenchRpc rpc = rpc(chat);
        assertThat(rpc.call(WorkbenchRpc.METHOD_SESSION_SEND, params("""
            {"session":"s1","text":"next"}
            """)).get("ok").asBoolean()).isTrue();
        assertThat(chat.sentSession).isEqualTo("s1");
        assertThat(chat.sentText).isEqualTo("next");

        assertRpcCode(() -> rpc.call(WorkbenchRpc.METHOD_SESSION_SEND, params("""
            {"session":"s1","text":"busy"}
            """)), -32006);
    }

    @Test
    void cancelDelegates() throws Exception {
        JsonNode result = rpc(chat).call(WorkbenchRpc.METHOD_SESSION_CANCEL, params("""
            {"session":"s1"}
            """));
        assertThat(result.get("ok").asBoolean()).isTrue();
        assertThat(chat.cancelled).isEqualTo("s1");
    }

    @Test
    void approvalRespondDelegatesAndPassesDomainErrors() throws Exception {
        WorkbenchRpc rpc = rpc(chat);
        rpc.call(WorkbenchRpc.METHOD_APPROVAL_RESPOND, params("""
            {"requestId":"r1","allow":false}
            """));
        assertThat(chat.respondedId).isEqualTo("r1");
        assertThat(chat.allow).isFalse();

        assertRpcCode(() -> rpc.call(WorkbenchRpc.METHOD_APPROVAL_RESPOND, params("""
            {"requestId":"dup","allow":true}
            """)), -32008);
        assertRpcCode(() -> rpc.call(WorkbenchRpc.METHOD_APPROVAL_RESPOND, params("""
            {"requestId":"late","allow":true}
            """)), -32009);
    }

    @Test
    void disabledWriteFaceAnswersMethodNotFound() throws Exception {
        WorkbenchRpc rpc = rpc(null);
        assertRpcCode(() -> rpc.call(WorkbenchRpc.METHOD_SESSION_START, params("""
            {"text":"hello"}
            """)), -32601);
        assertRpcCode(() -> rpc.call(WorkbenchRpc.METHOD_SESSION_SEND, params("""
            {"session":"s1","text":"next"}
            """)), -32601);
        assertRpcCode(() -> rpc.call(WorkbenchRpc.METHOD_SESSION_CANCEL, params("""
            {"session":"s1"}
            """)), -32601);
        assertRpcCode(() -> rpc.call(WorkbenchRpc.METHOD_APPROVAL_RESPOND, params("""
            {"requestId":"r1","allow":true}
            """)), -32601);
        assertThat(chat.startedText).isNull();
    }

    @Test
    void disabledWriteFaceHidesMethodsEvenWithMalformedParams() {
        WorkbenchRpc rpc = rpc(null);
        for (String method : List.of("session/start", "session/send", "session/cancel", "approval/respond")) {
            for (String json : List.of("null", "{}", "[]", "{\"text\":false,\"allow\":\"yes\"}")) {
                assertRpcCode(() -> rpc.call(method, params(json)), -32601);
            }
        }
    }

    @Test
    void invalidParamsRejectedBeforeSeam() throws Exception {
        WorkbenchRpc rpc = rpc(chat);
        assertRpcCode(() -> rpc.call(WorkbenchRpc.METHOD_SESSION_START, params("""
            {"text":""}
            """)), -32602);
        assertRpcCode(() -> rpc.call(WorkbenchRpc.METHOD_SESSION_SEND, params("""
            {"text":"x"}
            """)), -32602);
        assertRpcCode(() -> rpc.call(WorkbenchRpc.METHOD_APPROVAL_RESPOND, params("""
            {"requestId":"r1","allow":"yes"}
            """)), -32602);
        assertThat(chat.startedText).isNull();
    }

    private WorkbenchRpc rpc(ChatControl control) {
        return new WorkbenchRpc(persistence, tail, "dump", yaml -> List.of(), control);
    }

    /** 测试夹具 JSON 恒合法；受检包装消除 lambda 内 throws（Runnable 形态断言）。 */
    private JsonNode params(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid test fixture json", e);
        }
    }

    private static void assertRpcCode(Runnable action, int code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(RpcException.class,
                        error -> assertThat(error.code()).isEqualTo(code));
    }

    /** 假 seam：记录调用并模拟实现方域错误。 */
    private static final class FakeChat implements ChatControl {

        String startedText;
        String sentSession;
        String sentText;
        String cancelled;
        String respondedId;
        Boolean allow;

        @Override
        public String start(String text) {
            startedText = text;
            return "sess-new";
        }

        @Override
        public void send(String session, String text) {
            if ("busy".equals(text)) {
                throw new RpcException(-32006, "turn in flight");
            }
            sentSession = session;
            sentText = text;
        }

        @Override
        public void cancel(String session) {
            cancelled = session;
        }

        @Override
        public void respondApproval(String requestId, boolean allow) {
            if ("dup".equals(requestId)) {
                throw new RpcException(-32008, "approval already settled");
            }
            if ("late".equals(requestId)) {
                throw new RpcException(-32009, "approval expired");
            }
            respondedId = requestId;
            this.allow = allow;
        }
    }
}
