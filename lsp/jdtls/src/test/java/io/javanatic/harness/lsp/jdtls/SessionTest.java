package io.javanatic.harness.lsp.jdtls;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTest {

    @Test
    void linuxCommandAddsOneWrapperSeparatorAndPreservesArgv() {
        List<String> argv = List.of("java", "-jar", "server with spaces.jar", "--", "project root");

        // 期望值由 Path 派生而非字面量：Windows 的 Path 渲染用反斜杠分隔符，形状断言两平台同测。
        assertThat(Session.buildLinuxCommand(Path.of("/usr/bin/setsid"), argv))
            .containsExactly(Path.of("/usr/bin/setsid").toString(), "--", "java", "-jar",
                "server with spaces.jar", "--", "project root");
    }
}
