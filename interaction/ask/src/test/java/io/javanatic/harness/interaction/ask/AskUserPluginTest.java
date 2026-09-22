package io.javanatic.harness.interaction.ask;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.kernel.scope.ServiceNotAvailableException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.tools.ApprovalService;
import io.javanatic.harness.tools.Approvals;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutor;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolsPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ask_user 两个声明面的部署级证据：DENY_ALL 档下仍可问（免审批）、结果停轮
 * （concludesTurn 落账 + 成对留痕）、空问题落 error result 不停轮、缺工具面
 * fail loud 且整体回滚。
 */
class AskUserPluginTest {

    private static final String ASK =
        "{\"question\":\"  目标目录用哪个?  \"}";

    /** 全拒绝审批的最小 Provider：DENY_ALL 是免审批声明最严的试金石（ask 在它之下仍须通天）。 */
    private static Plugin denyApproval() {
        return new Plugin() {
            @Override
            public String id() {
                return "approval-deny-test";
            }

            @Override
            public void apply(Scope scope) {
                scope.provide(ApprovalService.KEY, Approvals.deny());
            }
        };
    }

    private static Runtime deployment() {
        Runtime rt = new Runtime();
        new PluginLoader().loadAll(rt, List.of(
            new SessionStorePlugin(), denyApproval(), new ToolsPlugin(), new AskUserPlugin()));
        return rt;
    }

    @Test
    void identityAndExemptionDeclaration() {
        try (Runtime rt = deployment()) {
            AskUserPlugin plugin = new AskUserPlugin();
            assertThat(plugin.id()).isEqualTo("ask-user");
            assertThat(plugin.requires()).containsExactly("tools");

            ToolDefinition ask = rt.root().require(ToolRegistry.KEY)
                .resolve(rt.root(), "ask_user").orElseThrow();
            assertThat(ask.approvalExempt()).isTrue(); // 声明面:治理摘要与 executor 同读它
            assertThat(ask.description()).contains("ends the turn");
        }
    }

    @Test
    void asksUnderDenyAllAndEndsTurn() {
        try (Runtime rt = deployment()) {
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            Session session = Session.create(Session.newId("ask"), null, null);
            List<LoggedEvent<ToolResultEvent>> results = executor.execute(List.of(
                new ToolUseBlock(CallId.of("q1"), "ask_user", ASK)),
                session, 0, 0, rt.root(), AbortSignal.never());

            ToolResultEvent result = results.getFirst().event();
            assertThat(result.block().isError()).isFalse(); // DENY_ALL 下仍通天:免审批声明兑现
            assertThat(result.block().content()).isEqualTo("目标目录用哪个?"); // canonical 形已 trim
            assertThat(result.concludesTurn()).isTrue(); // 停轮位随结果落账(R2)
            assertThat(session.events().stream().map(LoggedEvent::type))
                .containsExactly("tool/call", "tool/result");
        }
    }

    @Test
    void blankQuestionIsErrorResultAndDoesNotEndTurn() {
        try (Runtime rt = deployment()) {
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            Session session = Session.create(Session.newId("blank"), null, null);
            List<LoggedEvent<ToolResultEvent>> results = executor.execute(List.of(
                new ToolUseBlock(CallId.of("q2"), "ask_user", "{\"question\":\"   \"}")),
                session, 0, 0, rt.root(), AbortSignal.never());

            ToolResultEvent result = results.getFirst().event();
            assertThat(result.block().isError()).isTrue();
            assertThat(result.block().content()).contains("invalid question");
            assertThat(result.concludesTurn()).isFalse(); // 错误不是问答:轮继续,模型可再试
            assertThat(session.events()).hasSize(2);
        }
    }

    @Test
    void missingToolsFaceFailsLoudAndRollsBack() {
        try (Runtime rt = new Runtime()) {
            assertThatThrownBy(() ->
                new PluginLoader().loadAll(rt, List.of(new SessionStorePlugin(), new AskUserPlugin())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rolled back")
                .hasRootCauseInstanceOf(ServiceNotAvailableException.class)
                .getRootCause()
                .hasMessageContaining("tools");
        }
    }

    @Test
    void registryExposesExemptFaceForGovernance() {
        try (Runtime rt = deployment()) {
            assertThat(rt.root().require(ToolRegistry.KEY).definitions(rt.root()).stream()
                .filter(ToolDefinition::approvalExempt)
                .map(ToolDefinition::name))
                .containsExactly("ask_user"); // 治理自述:本部署仅问答免审批
        }
    }
}
