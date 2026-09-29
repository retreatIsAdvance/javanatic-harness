package io.javanatic.harness.sandbox.local;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 助手启动命令构造面（it25 S-a 抽取自 Landlock，两个助手共用）：三形态按运行
 * 事实择一 + 形态不自洽时 fail loud。平台无关——java 可执行路径按 {@code Path}
 * 语义断言（Windows 宿主渲染为反斜杠，写死 POSIX 形态必假红）。
 */
class HelperLaunchTest {

    @Test
    void classpathFormForUnnamedModule() {
        assertThat(HelperLaunch.command("/jdk", "/tmp/helper.jar", "", null, LandlockExecMain.class)
            .orElseThrow())
            .containsExactly(Path.of("/jdk", "bin", "java").toString(), "-XX:-UsePerfData",
                "-Dstderr.encoding=UTF-8",
                "--enable-native-access=ALL-UNNAMED",
                "-cp", "/tmp/helper.jar", LandlockExecMain.class.getName());
    }

    @Test
    void modulePathFormWhenModulePathPresent() {
        assertThat(HelperLaunch.command("/jdk", "", "/modules", "io.javanatic.harness.sandbox.local",
            WindowsAclExecMain.class).orElseThrow())
            .containsExactly(Path.of("/jdk", "bin", "java").toString(), "-XX:-UsePerfData",
                "-Dstderr.encoding=UTF-8",
                "--enable-native-access=io.javanatic.harness.sandbox.local",
                "--module-path", "/modules", "-m",
                "io.javanatic.harness.sandbox.local/" + WindowsAclExecMain.class.getName());
    }

    @Test
    void imageFormWhenModulePathAbsent() {
        assertThat(HelperLaunch.command("/image", "", "", "io.javanatic.harness.sandbox.local",
            LandlockExecMain.class).orElseThrow())
            .containsExactly(Path.of("/image", "bin", "java").toString(), "-XX:-UsePerfData",
                "-Dstderr.encoding=UTF-8",
                "--enable-native-access=io.javanatic.harness.sandbox.local", "-m",
                "io.javanatic.harness.sandbox.local/" + LandlockExecMain.class.getName());
    }

    @Test
    void withoutDecidableFormIsEmpty() {
        assertThat(HelperLaunch.command("/jdk", "", "", null, LandlockExecMain.class)).isEmpty();
        assertThat(HelperLaunch.command("/jdk", null, "/modules", null, LandlockExecMain.class)).isEmpty();
    }

    @Test
    void hostCommandNamesTheGivenEntryClass() {
        List<String> command = HelperLaunch.hostCommand(WindowsAclExecMain.class).orElseThrow();
        assertThat(command).last().asString().endsWith(WindowsAclExecMain.class.getName());
    }
}
