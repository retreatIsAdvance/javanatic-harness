package io.javanatic.harness.boot;

import io.javanatic.harness.interaction.approval.ApprovalPrompt;
import io.javanatic.harness.kernel.config.CompositionManifest;
import io.javanatic.harness.kernel.config.ConfigRowSpec;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.tools.ApprovalDeniedException;
import io.javanatic.harness.tools.ApprovalService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H1（it38 停 2 双裁）：AppBoot 仅 ApprovalPrompt 类型的启动期注入口。
 * 只替换已启用 ask 行；ask 未启用 / 类型不符 fail loud；行序与计数不变；
 * 失败路径关闭半装配 Runtime（R3）。
 */
class AppBootPromptInjectionTest {

    @TempDir
    Path dir;

    private Path profile() throws Exception {
        Path ws = dir.resolve("ws");
        ws.toFile().mkdir();
        Path file = dir.resolve("profile.yml");
        Files.writeString(file, """
            name: test
            policy: standard
            bundles: [base]
            rows:
            """);
        return file;
    }

    private List<ConfigRowSpec> overlays() {
        Path ws = dir.resolve("ws");
        Path sess = dir.resolve("sessions");
        ws.toFile().mkdir();
        return List.of(
            new ConfigRowSpec.Replace("fs-local", Map.of("root", ws.toString()), null),
            new ConfigRowSpec.Replace("shell-tool",
                Map.of("workspace", ws.toString(), "timeoutSeconds", 30), null),
            new ConfigRowSpec.Replace("sandbox-policy",
                Map.of("mode", "workspace-write", "workspace", ws.toString()), null),
            new ConfigRowSpec.Replace("agent-loop", Map.of("cwd", ws.toString()), null),
            new ConfigRowSpec.Replace("skill-local", Map.of("cwd", ws.toString()), null),
            new ConfigRowSpec.Replace("persistence-jsonl", Map.of("root", sess.toString()), null));
    }

    /** 人闸档：ask 启用、auto/deny 禁用（与 headless --approval=ask 的 overlay 同形）。 */
    private List<ConfigRowSpec> askOverlays() {
        List<ConfigRowSpec> rows = new java.util.ArrayList<>(overlays());
        rows.add(new ConfigRowSpec.Replace("approval-ask", Map.of(), null));
        rows.add(new ConfigRowSpec.Replace("approval-auto", Map.of(), "true"));
        rows.add(new ConfigRowSpec.Replace("approval-deny", Map.of(), "true"));
        return rows;
    }

    private AppBoot.BootOptions askBoot() throws Exception {
        return new AppBoot.BootOptions(profile(), askOverlays(), false, null);
    }

    private static ApprovalService.ApprovalRequest request() {
        return new ApprovalService.ApprovalRequest("t", "t args", "{}");
    }

    @Test
    void injectedPromptBacksTheEnabledAskService() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        ApprovalPrompt prompt = (approval, signal) -> {
            asked.incrementAndGet();
            return true;
        };
        try (Runtime rt = AppBoot.bootReported(askBoot(), prompt).runtime()) {
            ApprovalService approval = rt.root().require(ApprovalService.KEY);
            assertThat(approval.mode()).isEqualTo(ApprovalService.Mode.HUMAN_GATE);
            approval.require(request(), AbortSignal.never());
            assertThat(asked).hasValue(1);
        }
    }

    @Test
    void injectedPromptDenialPropagatesAsDenied() throws Exception {
        ApprovalPrompt prompt = (approval, signal) -> false;
        try (Runtime rt = AppBoot.bootReported(askBoot(), prompt).runtime()) {
            ApprovalService approval = rt.root().require(ApprovalService.KEY);
            assertThatThrownBy(() -> approval.require(request(), AbortSignal.never()))
                .isInstanceOf(ApprovalDeniedException.class)
                .hasMessageContaining("denied by human gate");
        }
    }

    @Test
    void promptInjectionFailsLoudWhenAskIsNotEnabled() throws Exception {
        AppBoot.BootOptions autoBoot = new AppBoot.BootOptions(profile(), overlays(), false, null);
        assertThatThrownBy(() -> AppBoot.bootReported(autoBoot, (approval, signal) -> true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("approval-ask");
    }

    @Test
    void promptInjectionNeverReplacesDenyOrAuto() throws Exception {
        List<ConfigRowSpec> denyRows = new java.util.ArrayList<>(overlays());
        denyRows.add(new ConfigRowSpec.Replace("approval-ask", Map.of(), "true"));
        denyRows.add(new ConfigRowSpec.Replace("approval-auto", Map.of(), "true"));
        denyRows.add(new ConfigRowSpec.Replace("approval-deny", Map.of(), null));
        AppBoot.BootOptions denyBoot = new AppBoot.BootOptions(profile(), denyRows, false, null);
        assertThatThrownBy(() -> AppBoot.bootReported(denyBoot, (approval, signal) -> true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("approval-ask");
    }

    @Test
    void promptInjectionKeepsRowsAndCountsInvariant() throws Exception {
        AppBoot.Booted plain = AppBoot.bootReported(askBoot());
        AppBoot.Booted injected = AppBoot.bootReported(askBoot(), (approval, signal) -> true);
        try (Runtime a = plain.runtime(); Runtime b = injected.runtime()) {
            assertThat(injected.discovered()).isEqualTo(plain.discovered());
            assertThat(injected.unreferenced()).isEqualTo(plain.unreferenced());
            CompositionManifest.Row[] before = a.root().require(CompositionManifest.KEY).rows()
                .toArray(CompositionManifest.Row[]::new);
            assertThat(b.root().require(CompositionManifest.KEY).rows()).containsExactly(before);
        }
    }

    /** 同 id 但不是 ApprovalAskPlugin → 类型不符 fail loud（不静默换错实现）。 */
    @Test
    void promptInjectionRejectsLookalikePluginType() {
        Map<String, Plugin> discovered = new HashMap<>();
        discovered.put("approval-ask", new Plugin() {
            @Override
            public String id() {
                return "approval-ask";
            }

            @Override
            public void apply(Scope scope) {
            }
        });
        List<ConfigRowSpec> enabled = List.of(
            new ConfigRowSpec.Include("approval-ask", Map.of(), null));
        assertThatThrownBy(() -> AppBoot.injectApprovalPrompt(
            discovered, enabled, (approval, signal) -> true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("类型不符");
    }

    /** R3：装配失败（policy 违规）时半装配 Runtime 已被关闭，原始违规仍上抛。 */
    @Test
    void failedAssemblyClosesTheRuntimeAndKeepsCause() throws Exception {
        AppBoot.BootOptions options = new AppBoot.BootOptions(profile(), overlays(), true,
            Policy.PRODUCTION);
        Runtime rt = new Runtime();
        assertThatThrownBy(() -> AppBoot.assemble(rt, options, null))
            .isInstanceOf(AppBoot.VerifyFailedException.class)
            .hasMessageContaining("AUTO");
        assertThat(rt.root().isClosed()).isTrue();
    }
}
