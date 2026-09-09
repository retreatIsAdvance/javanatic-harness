package io.javanatic.harness.examples.agent.spine;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import io.javanatic.harness.agent.AgentPlugin;
import io.javanatic.harness.agentloop.AgentLoopPlugin;
import io.javanatic.harness.fs.FsService;
import io.javanatic.harness.fs.local.FsLocalPlugin;
import io.javanatic.harness.fs.tool.FsToolPlugin;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.LlmService;
import io.javanatic.harness.llm.replay.ReplayPlugin;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.systemprompt.SystemPromptService;
import io.javanatic.harness.tools.ToolExecutor;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.importer.Location;

import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2 执行一致性的架构断言（10 §6）：全库唯一分发点。落在 examples/agent-spine
 * ——只有这里的 classpath 看得到全部生产模块，「全库唯一」断言才有资格下。
 * 类导入经锚类定位（每模块一个公开类的 CodeSource）：不依赖 surefire 的
 * classpath 形态（manifest-boot jar 下 java.class.path 无真实条目）。
 */
class ToolDispatchArchitectureTest {

    private static final String LOOP = AgentLoopPlugin.class.getPackageName() + ".AgentLoopImpl";
    private static final String EXECUTOR = ToolExecutor.class.getPackageName() + ".ToolExecutorImpl";

    private static final JavaClasses CLASSES = importProductionClasses();

    /** 模型 toolCalls 的执行分发点全库唯一：只有 AgentLoopImpl 调 ToolExecutor.execute。 */
    private static final ArchRule ONLY_LOOP_DISPATCHES_MODEL_TOOL_CALLS = noClasses()
        .that().doNotHaveFullyQualifiedName(LOOP)
        .should().callMethod(ToolExecutor.class, "execute",
            List.class, Session.class, int.class, int.class,
        Scope.class, AbortSignal.class)
        .because("R2：新增第二条分发路径（如插件直接 execute 模型 toolCall）即破不变式");

    /** tool/result 审计落账归属 executor：只有 ToolExecutorImpl 构造 ToolResultEvent。 */
    private static final ArchRule ONLY_EXECUTOR_BUILDS_TOOL_RESULT_EVENTS = noClasses()
        .that().doNotHaveFullyQualifiedName(EXECUTOR)
        .should().callConstructor(ToolResultEvent.class,
            long.class, int.class, int.class, ToolResultBlock.class, boolean.class, SurfaceOp.class, List.class)
        .because("R2：绕过 executor 构造结果事件 = 执行了但不留痕");

    @Test
    void onlyLoopDispatchesModelToolCalls() {
        ONLY_LOOP_DISPATCHES_MODEL_TOOL_CALLS.check(CLASSES);
    }

    @Test
    void onlyExecutorBuildsToolResultEvents() {
        ONLY_EXECUTOR_BUILDS_TOOL_RESULT_EVENTS.check(CLASSES);
    }

    /** 前置健全性：两条规则的目标类确实在扫描范围（防空规则假绿）。 */
    @Test
    void ruleTargetsExist() {
        assertThat(CLASSES.stream().map(clazz -> clazz.getName()).toList())
            .contains(LOOP, EXECUTOR);
    }

    /**
     * 每模块一个锚类，CodeSource 定位其类根。依赖可能是目录（target/classes）或
     * jar（reactor 打包形态），统一转 ArchUnit Location：file 协议走 Path（目录
     * 无尾斜杠也能导入），其余（jar:）走 Location.of(URL)。
     */
    private static JavaClasses importProductionClasses() {
        List<Location> roots = Stream.of(
                Runtime.class, Id.class, Session.class, ToolExecutor.class,
                SystemPromptService.class, AgentPlugin.class, AgentLoopPlugin.class,
                LlmService.class, ReplayPlugin.class, FsService.class,
                FsLocalPlugin.class, FsToolPlugin.class, SpineMain.class)
            .map(ToolDispatchArchitectureTest::anchorLocation)
            .distinct()
            .toList();
        return new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importLocations(roots);
    }

    private static Location anchorLocation(Class<?> anchor) {
        URL url = anchor.getProtectionDomain().getCodeSource().getLocation();
        try {
            if ("file".equals(url.getProtocol())) {
                if (url.getPath().endsWith(".jar")) {
                    // jar 的 CodeSource 是 file: 协议指向 jar 文件——转 jar: URI 才是导入源
                    return Location.of(URI.create("jar:" + url.toExternalForm() + "!/").toURL());
                }
                return Location.of(Path.of(url.toURI()));
            }
            return Location.of(url);
        } catch (Exception e) {
            throw new IllegalStateException("anchor class location not importable: " + anchor, e);
        }
    }
}
