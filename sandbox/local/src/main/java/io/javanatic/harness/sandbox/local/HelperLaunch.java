package io.javanatic.harness.sandbox.local;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 本模块两个助手（{@link LandlockExecMain} / {@link WindowsAclExecMain}）的
 * 启动命令构造面（it25 S-a 抽取自 Landlock，避免两份漂移）：固定带
 * {@code -XX:-UsePerfData}（避免助手 JVM 启动落 hsperfdata 侧文件）与
 * {@code --enable-native-access}（FFM 受限方法授权；具名模块点模块名，
 * unnamed 用 ALL-UNNAMED）。启动形态按运行事实三选一（非猜测）：classpath 态
 * → {@code -cp}；模块路径态 → {@code --module-path + -m}；镜像态（两者皆空，
 * 模块在镜像内）→ {@code -m}。
 */
final class HelperLaunch {

    private HelperLaunch() {
    }

    /**
     * @param javaHome   JVM 主目录（镜像态 = 镜像根，其 bin/java 即自带 JVM）
     * @param classPath  unnamed 形态的 classpath（唯一需要的条目 = 助手类所在目录/jar）
     * @param modulePath 模块路径态的非空 {@code jdk.module.path}；镜像态为空串
     * @param moduleName 助手类的具名模块名；unnamed 时为 null
     * @param mainClass  助手入口类
     * @return 助手命令；形态不自洽（unnamed 无 classpath）时 empty
     */
    static Optional<List<String>> command(String javaHome, String classPath, String modulePath,
                                          String moduleName, Class<?> mainClass) {
        List<String> command = new ArrayList<>(List.of(
            Path.of(javaHome, "bin", "java").toString(), "-XX:-UsePerfData"));
        if (moduleName == null) {
            if (classPath == null || classPath.isEmpty()) {
                return Optional.empty();
            }
            command.add("--enable-native-access=ALL-UNNAMED");
            command.add("-cp");
            command.add(classPath);
            command.add(mainClass.getName());
            return Optional.of(List.copyOf(command));
        }
        command.add("--enable-native-access=" + moduleName);
        if (modulePath != null && !modulePath.isEmpty()) {
            command.add("--module-path");
            command.add(modulePath);
        }
        command.add("-m");
        command.add(moduleName + "/" + mainClass.getName());
        return Optional.of(List.copyOf(command));
    }

    /**
     * 本进程的助手启动命令（provider 与测试共用的事实收集点）：具名模块走模块面
     * （模块路径态带 {@code jdk.module.path}，镜像态该属性为空、模块在镜像内）；
     * unnamed 走助手类<b>自身 code source</b> 作 classpath——{@code java.class.path}
     * 在 surefire 等宿主里是只有 Class-Path 清单的 booter jar、无真实条目（it24 实探），
     * 拿它当 classpath 会起一个类都找不到的 JVM。
     *
     * @param mainClass 助手入口类
     * @return 助手命令；形态不可判定时 empty（调用方 fail-closed 并点名）
     */
    static Optional<List<String>> hostCommand(Class<?> mainClass) {
        String javaHome = System.getProperty("java.home");
        if (mainClass.getModule().isNamed()) {
            return command(javaHome, "", System.getProperty("jdk.module.path", ""),
                mainClass.getModule().getName(), mainClass);
        }
        return codeSourceOf(mainClass).flatMap(classPath -> command(javaHome, classPath, "", null, mainClass));
    }

    /** 助手类的 code source 路径（目录或 jar）；不可判定时 empty。 */
    private static Optional<String> codeSourceOf(Class<?> type) {
        try {
            var source = type.getProtectionDomain().getCodeSource();
            return source == null ? Optional.empty()
                : Optional.of(Path.of(source.getLocation().toURI()).toString());
        } catch (URISyntaxException | IllegalArgumentException undeterminable) {
            return Optional.empty();
        }
    }
}
