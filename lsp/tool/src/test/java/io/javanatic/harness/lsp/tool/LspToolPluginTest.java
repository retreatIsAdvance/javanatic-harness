package io.javanatic.harness.lsp.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.lsp.LspDiagnostic;
import io.javanatic.harness.lsp.LspErrorCode;
import io.javanatic.harness.lsp.LspException;
import io.javanatic.harness.lsp.LspLocation;
import io.javanatic.harness.lsp.LspPositionQuery;
import io.javanatic.harness.lsp.LspQuery;
import io.javanatic.harness.lsp.LspResult;
import io.javanatic.harness.lsp.LspService;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.RenderIntent;
import io.javanatic.harness.tools.ToolArgs;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionContext;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolsPlugin;
import io.javanatic.harness.tools.ValueSchema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 消费面装配与执行（停 3 评审重点）：双态（Provider 缺席零工具）、三工具恒需审批、
 * LOCATIONS 意图、逐字措辞接线、LspException → error、取消穿透、参数预算校验。
 */
class LspToolPluginTest {

    @Test
    void pluginShapeRequiresOnlyTools() {
        LspToolPlugin plugin = new LspToolPlugin();
        assertThat(plugin.id()).isEqualTo("lsp-tool");
        assertThat(plugin.requires()).isEqualTo(Set.of("tools"));
    }

    @Test
    void absentProviderRegistersNoLspTools() {
        try (Runtime rt = boot(null, Map.of())) {
            assertThat(names(rt)).isEmpty();
        }
    }

    @Test
    void presentProviderRegistersThreeApprovalRequiredTools() {
        try (Runtime rt = boot(new FakeLspService(LspToolFixtures.quietDiagnostics(),
            LspToolFixtures.observedLocations(List.of()), null), Map.of())) {
            assertThat(names(rt)).containsExactly(
                "lsp_definition", "lsp_diagnostics", "lsp_references");
            ToolRegistry registry = rt.root().require(ToolRegistry.KEY);
            ToolDefinition diagnostics = registry.resolve(rt.root(), "lsp_diagnostics").orElseThrow();
            assertThat(diagnostics.approvalExempt()).isFalse();
            assertThat(diagnostics.render()).isEqualTo(RenderIntent.GENERIC);
            assertThat(diagnostics.description())
                .contains("拉取时点观察")
                .doesNotContain("项目无错误");
            assertThat(diagnostics.parameters()).isInstanceOfSatisfying(ValueSchema.Object.class,
                schema -> assertThat(schema.properties()).containsOnlyKeys(
                    "document", "timeoutSeconds", "waitSeconds"));
            for (String name : List.of("lsp_definition", "lsp_references")) {
                ToolDefinition tool = registry.resolve(rt.root(), name).orElseThrow();
                assertThat(tool.approvalExempt()).isFalse();
                assertThat(tool.render()).isEqualTo(RenderIntent.LOCATIONS);
                assertThat(tool.parameters()).isInstanceOfSatisfying(ValueSchema.Object.class,
                    schema -> assertThat(schema.properties()).containsOnlyKeys(
                        "document", "line", "column"));
            }
        }
    }

    @Test
    void diagnosticsExecutionRendersVerbatimQuietWindow() throws Exception {
        try (Runtime rt = boot(new FakeLspService(LspToolFixtures.quietDiagnostics(),
            LspToolFixtures.observedLocations(List.of()), null), Map.of())) {
            ToolExecutionResult result = run(rt, "lsp_diagnostics",
                "{\"document\":\"/proj/src/main/App.java\"}");
            assertThat(result.isError()).isFalse();
            assertThat(result.content()).isEqualTo("拉取成功（hash bbbbbbbb）、项目代稳定（前后锚相同）、"
                + "完整等待窗（elapsed 16.0s ≥ required 15.0s），拉取时点无诊断。");
        }
    }

    @Test
    void definitionExecutionRendersZeroLocations() throws Exception {
        try (Runtime rt = boot(new FakeLspService(LspToolFixtures.quietDiagnostics(),
            LspToolFixtures.observedLocations(List.of()), null), Map.of())) {
            ToolExecutionResult result = run(rt, "lsp_definition",
                "{\"document\":\"/proj/src/main/App.java\",\"line\":5,\"column\":2}");
            assertThat(result.isError()).isFalse();
            assertThat(result.content()).isEqualTo("拉取成功（hash bbbbbbbb）；本次请求返回 0 个位置。");
        }
    }

    @Test
    void lspExceptionBecomesCodedErrorResult() throws Exception {
        try (Runtime rt = boot(new FakeLspService(LspToolFixtures.quietDiagnostics(),
            LspToolFixtures.observedLocations(List.of()),
            new LspException(LspErrorCode.UNSUPPORTED, "document outside project root")), Map.of())) {
            ToolExecutionResult result = run(rt, "lsp_diagnostics",
                "{\"document\":\"/proj/src/main/App.java\"}");
            assertThat(result.isError()).isTrue();
            assertThat(result.content()).isEqualTo("LSP 错误（UNSUPPORTED）：document outside project root");
        }
    }

    @Test
    void cancellationPropagatesUnwrapped() {
        try (Runtime rt = boot(new FakeLspService(LspToolFixtures.quietDiagnostics(),
            LspToolFixtures.observedLocations(List.of()),
            new AbortedException("cancelled")), Map.of())) {
            assertThatThrownBy(() -> run(rt, "lsp_diagnostics",
                "{\"document\":\"/proj/src/main/App.java\"}"))
                .isInstanceOf(AbortedException.class);
        }
    }

    @Test
    void waitWindowExceedingTimeoutFailsTheCallWithArgumentError() throws Exception {
        try (Runtime rt = boot(new FakeLspService(LspToolFixtures.quietDiagnostics(),
            LspToolFixtures.observedLocations(List.of()), null), Map.of())) {
            ToolExecutionResult result = run(rt, "lsp_diagnostics",
                "{\"document\":\"/proj/src/main/App.java\",\"timeoutSeconds\":10,\"waitSeconds\":20}");
            assertThat(result.isError()).isTrue();
            assertThat(result.content()).contains("waitSeconds").contains("timeoutSeconds");
        }
    }

    private static Runtime boot(LspService service, Map<String, Object> config) {
        Runtime rt = new Runtime();
        if (service != null) {
            rt.root().provide(LspService.KEY, service);
        }
        rt.root().provide(ConfigService.KEY, id -> config);
        new PluginLoader().loadAll(rt, List.of(
            new ApprovalAutoPlugin(), new SessionStorePlugin(), new ToolsPlugin(),
            new LspToolPlugin()));
        return rt;
    }

    private static List<String> names(Runtime rt) {
        return rt.root().require(ToolRegistry.KEY).definitions(rt.root()).stream()
            .map(ToolDefinition::name).sorted().toList();
    }

    private static ToolExecutionResult run(Runtime rt, String name, String argsJson) throws Exception {
        ToolRegistry registry = rt.root().require(ToolRegistry.KEY);
        ToolDefinition definition = registry.resolve(rt.root(), name).orElseThrow();
        Session session = Session.create(Session.newId("lsp-tool-run"), null, null);
        return definition.tool().execute(ToolArgs.parse(argsJson, definition.parameters()),
            new ToolExecutionContext(AbortSignal.never(), session));
    }

    /** 假服务：diagnostics/locations 返回预置结果，或在调用时抛预置异常。 */
    private static class FakeLspService implements LspService {
        private final LspResult<LspDiagnostic> diagnostics;
        private final LspResult<LspLocation> locations;
        private final RuntimeException failure;

        FakeLspService(LspResult<LspDiagnostic> diagnostics, LspResult<LspLocation> locations,
                       RuntimeException failure) {
            this.diagnostics = diagnostics;
            this.locations = locations;
            this.failure = failure;
        }

        @Override
        public LspResult<LspDiagnostic> diagnostics(LspQuery request, AbortSignal signal) {
            if (failure != null) {
                throw failure;
            }
            return diagnostics;
        }

        @Override
        public LspResult<LspLocation> definition(LspPositionQuery request, AbortSignal signal) {
            if (failure != null) {
                throw failure;
            }
            return locations;
        }

        @Override
        public LspResult<LspLocation> references(LspPositionQuery request, AbortSignal signal) {
            if (failure != null) {
                throw failure;
            }
            return locations;
        }

        @Override
        public void close() {
        }
    }
}
