package io.javanatic.harness.examples.headless;

import com.sun.net.httpserver.HttpServer;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.FailureKind;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEndSeedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.MessageSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * it17 one-shot 结果契约：stdout = 成功有结果 / 失败为空;退出码 0/3/4
 * （Completed/Error/Aborted,无 turn/end 归 3——词表见 USAGE 与 12 §6）;
 * 失败诊断（文案 + 模型遗言）走 stderr;本进程新开轮边界 = seq ≥ firstLiveSeq(),
 * resume 不误判旧轮。假服务端直穿 {@code HeadlessMain.run}（含 401 与预算两条失败路径）。
 */
class HeadlessOneShotResultTest {

    private static final MessageSource.Model MODEL = new MessageSource.Model("replay", "m");

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    private HttpServer server;
    private volatile int status;
    private volatile String body;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",
                status == 200 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    // ────────── 端到端：退出码 + stdout 契约 ──────────

    @Test
    void successPrintsAnswerToStdoutAndExitsZero() throws Exception {
        answerWith("回答完成");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = run(new String[] {"测试任务"}, stdout, stderr);
        assertThat(exit).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
            .isEqualTo("回答完成" + System.lineSeparator());
        assertThat(stderr.toString(StandardCharsets.UTF_8)).doesNotContain("任务失败");
    }

    @Test
    void emptyFinalTextIsStillSuccessWithEmptyStdout() throws Exception {
        answerEmpty();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = run(new String[] {"测试任务"}, stdout, new ByteArrayOutputStream());
        assertThat(exit).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void authFailureExitsThreeWithEmptyStdoutAndActionableStderr() throws Exception {
        failAuth();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = run(new String[] {"测试任务"}, stdout, stderr);
        assertThat(exit).isEqualTo(3);
        assertThat(stdout.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(stderr.toString(StandardCharsets.UTF_8))
            .contains("任务失败", "认证失败", "401");
    }

    @Test
    void budgetRejectionExitsThreeWithEmptyStdout() throws Exception {
        Path note = Files.writeString(workspace.resolve("note.txt"), "note");
        answerToolCall(note, 60);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = run(new String[] {"测试任务", "--budget=50"}, stdout, stderr);
        assertThat(exit).isEqualTo(3);
        assertThat(stdout.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("token budget exceeded");
    }

    @Test
    void resumedFailureDoesNotReplayOldTurnAnswerOrTerminal() throws Exception {
        // 第一轮:成功落盘,旧轮有答案与 Completed 终局
        answerWith("第一轮答案");
        ByteArrayOutputStream stdout1 = new ByteArrayOutputStream();
        assertThat(run(new String[] {"第一轮"}, stdout1, new ByteArrayOutputStream())).isZero();
        assertThat(stdout1.toString(StandardCharsets.UTF_8))
            .isEqualTo("第一轮答案" + System.lineSeparator());
        String sessionId = findSessionId();

        // 第二轮:同会话 resume + 401 失败——stdout 必须为空(不回放旧轮答案),
        // stderr 不出现旧轮文本(遗言只取本进程新开轮)
        failAuth();
        ByteArrayOutputStream stdout2 = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr2 = new ByteArrayOutputStream();
        int second = run(new String[] {"--resume=" + sessionId, "第二轮"}, stdout2, stderr2);
        assertThat(second).isEqualTo(3);
        assertThat(stdout2.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(stderr2.toString(StandardCharsets.UTF_8))
            .contains("认证失败")
            .doesNotContain("第一轮答案");
    }

    // ────────── 单元：映射与 seed 边界 ──────────

    @Test
    void exitCodeMappingCoversTerminalReasonsAndFailsLoudOnUnknown() {
        assertThat(HeadlessMain.oneShotExitCode(live(
            new TurnEnd(0, 1, new TurnEndReason.Completed())))).isZero();
        assertThat(HeadlessMain.oneShotExitCode(live(
            new TurnEnd(0, 1, new TurnEndReason.Error("boom", FailureKind.UNKNOWN))))).isEqualTo(3);
        assertThat(HeadlessMain.oneShotExitCode(live(
            new TurnEnd(0, 1, new TurnEndReason.Aborted("user"))))).isEqualTo(4);
        assertThat(HeadlessMain.oneShotExitCode(List.of())).isEqualTo(3);
        assertThat(HeadlessMain.oneShotExitCode(live(
            new TurnEnd(0, 1, new UnknownReason())))).isEqualTo(3);
    }

    @Test
    void seedBoundaryKeepsOldTurnOutOfAnswerAndTerminal() {
        // seed(上一生命周期):旧轮答案 + Completed 终局 + end-seed 标记
        Session session = Session.create(Session.newId("it17-resume"), List.of(
            new TurnStart(0, 1),
            assistant(1, "旧轮答案"),
            new TurnEnd(0, 1, new TurnEndReason.Completed()),
            new SessionEndSeedEvent(0)), null);
        List<LoggedEvent<? extends SessionEvent>> live = HeadlessMain.liveEvents(session);
        assertThat(live).isEmpty();
        assertThat(HeadlessMain.finalAnswerText(live)).isEmpty();
        assertThat(HeadlessMain.oneShotExitCode(live)).isEqualTo(3); // 旧 Completed 不顶替本轮

        // 本进程新开轮:新答案 + Completed
        session.append(new TurnStart(0, 2));
        session.append(assistant(2, "新轮答案"));
        session.append(new TurnEnd(0, 2, new TurnEndReason.Completed()));
        live = HeadlessMain.liveEvents(session);
        assertThat(live).hasSize(3);
        assertThat(HeadlessMain.finalAnswerText(live)).isEqualTo("新轮答案");
        assertThat(HeadlessMain.oneShotExitCode(live)).isZero();
    }

    // ────────── 装配 ──────────

    private int run(String[] args, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr)
            throws Exception {
        String[] full = Arrays.copyOf(args, args.length + 4);
        full[args.length] = "--provider=vendor-x";
        full[args.length + 1] = "--model=x-1";
        full[args.length + 2] = "--base-url=http://localhost:" + server.getAddress().getPort();
        full[args.length + 3] = "--api-key=fake-key";
        return HeadlessMain.run(HeadlessMain.parse(full), workspace, sessions,
            new BufferedReader(new StringReader("")),
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }

    private String findSessionId() throws Exception {
        try (var dirs = Files.list(sessions)) {
            return dirs.filter(Files::isDirectory).findFirst().orElseThrow().getFileName().toString();
        }
    }

    private static List<LoggedEvent<? extends SessionEvent>> live(SessionEvent... events) {
        List<LoggedEvent<? extends SessionEvent>> list = new ArrayList<>();
        for (int i = 0; i < events.length; i++) {
            list.add(new LoggedEvent<SessionEvent>(i, events[i]));
        }
        return list;
    }

    private static AssistantMessageEvent assistant(int turn, String text) {
        return new AssistantMessageEvent(0, turn, 0, AssistantMessage.of(text, MODEL), null,
            new SurfaceOp.Append(), null);
    }

    /** 200:单条回复 + stop。 */
    private void answerWith(String text) {
        status = 200;
        body = "data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n";
    }

    /** 200:空答案 stop(合法成功)。 */
    private void answerEmpty() {
        status = 200;
        body = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n";
    }

    /** 401:错误体(认证失败,不重试)。 */
    private void failAuth() {
        status = 401;
        body = "{\"error\":{\"message\":\"invalid api key\"}}";
    }

    /** 200:一步工具调用(读文件)+ 用量——预算计量的多步脚本。 */
    private void answerToolCall(Path file, long outputTokens) {
        status = 200;
        String arguments = "{\"path\":\"" + file.toString().replace("\\", "\\\\") + "\"}";
        String escaped = arguments.replace("\\", "\\\\").replace("\"", "\\\"");
        body = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
            + "\"function\":{\"name\":\"fs_read\",\"arguments\":\"" + escaped + "\"}}]},"
            + "\"finish_reason\":\"tool_calls\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":" + outputTokens + "}}\n\n"
            + "data: [DONE]\n\n";
    }

    /** 开放接口的未来变体:映射必须 fail loud(不为成功)。 */
    private record UnknownReason() implements TurnEndReason {
    }
}
