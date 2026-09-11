package io.javanatic.harness.sandbox.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 可写根单一来源：模式语义、规范化（darwin /tmp→/private/tmp）、去重。 */
class WritableRootsTest {

    @TempDir
    Path workspace;

    @Test
    void readOnlyGrantsNothing() {
        assertThat(WritableRoots.of(new SandboxPolicy(SandboxMode.READ_ONLY, workspace))).isEmpty();
    }

    @Test
    void dangerGrantsNothingListable() {
        // 透传档不消费 allow-list（confine 也不接受它）——语义上空表
        assertThat(WritableRoots.of(new SandboxPolicy(SandboxMode.DANGER_FULL_ACCESS, workspace))).isEmpty();
    }

    @Test
    void workspaceWriteGrantsCanonicalWorkspaceAndTemp() throws Exception {
        assertThat(WritableRoots.of(new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace)))
            .contains(workspace.toRealPath(),
                Path.of(System.getProperty("java.io.tmpdir")).toRealPath());
    }
}
