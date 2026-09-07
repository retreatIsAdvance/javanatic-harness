package io.javanatic.harness.examples.headless;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 任意兼容厂商直跑证据:--base-url 指向本地假服务端,假 key 跑通完整 turn。 */
class HeadlessFakeServerE2ETest {

    private HttpServer server;

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"回答完成\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void customVendorViaFlagsRunsFullTurn() throws Exception {
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(new String[] {
            "测试任务",
            "--provider=vendor-x",
            "--model=x-1",
            "--base-url=http://localhost:" + server.getAddress().getPort(),
            "--api-key=fake-key"});
        assertThat(HeadlessMain.run(options, workspace, sessions)).isZero();
    }
}
