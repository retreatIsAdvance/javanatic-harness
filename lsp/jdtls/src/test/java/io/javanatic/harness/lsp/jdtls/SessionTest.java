package io.javanatic.harness.lsp.jdtls;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTest {

    @Test
    void linuxCommandAddsOneWrapperSeparatorAndPreservesArgv() {
        List<String> argv = List.of("java", "-jar", "server with spaces.jar", "--", "project root");

        assertThat(Session.buildLinuxCommand(Path.of("/usr/bin/setsid"), argv))
            .containsExactly("/usr/bin/setsid", "--", "java", "-jar", "server with spaces.jar", "--", "project root");
    }
}
