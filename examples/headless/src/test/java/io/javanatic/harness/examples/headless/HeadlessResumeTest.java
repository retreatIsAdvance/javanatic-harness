package io.javanatic.harness.examples.headless;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.net.InetSocketAddress;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 跨进程 resume:进程 A 跑一轮 → 落盘 → 进程 B(新装配)load+resume 续轮号。 */
class HeadlessResumeTest {

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    @Test
    void resumeContinuesTurnNumberingAcrossRuntimes() throws Exception {
        // 进程 A:跑一轮假服务端任务(dispose 后落盘)
        HeadlessMain.RunnerOptions first = HeadlessMain.parse(new String[] {
            "第一轮", "--provider=vendor-a", "--base-url=" + fakeServerUrl(),
            "--api-key=fake"});
        assertThat(HeadlessMain.run(first, workspace, sessions)).isZero();
        String sessionId = findSessionId();
        assertThat(Files.readString(sessions.resolve(sessionId).resolve("log.jsonl")))
            .contains("turn/start");

        // 进程 B:新 Runtime、同会话根 → load → resume 续跑一轮
        HeadlessMain.RunnerOptions second = HeadlessMain.parse(new String[] {
            "--resume=" + sessionId, "--provider=vendor-b", "--base-url=" + fakeServerUrl(),
            "--api-key=fake", "第二轮"});
        assertThat(HeadlessMain.run(second, workspace, sessions)).isZero();
        String log = Files.readString(sessions.resolve(sessionId).resolve("log.jsonl"));
        // 轮号连续:两次 turn/start + resume 分界(session/end-seed 由 Session 构造器标记)
        assertThat(log.split("turn/start", -1).length - 1).isGreaterThanOrEqualTo(2);
        assertThat(log).contains("session/end-seed");
    }

    private String findSessionId() throws Exception {
        try (var dirs = Files.list(sessions)) {
            return dirs.filter(Files::isDirectory).findFirst().orElseThrow().getFileName().toString();
        }
    }

    /** 起一个本地假 OpenAI 兼容服务端(每个会话一个脚本应答)。 */
    private String fakeServerUrl() {
        return SERVER_URL;
    }

    private static final String SERVER_URL = startServer();

    private static String startServer() {
        try {
            HttpServer server = HttpServer.create(
                new InetSocketAddress("localhost", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n").getBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return "http://localhost:" + server.getAddress().getPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
