package io.github.retreatisadvance.sample.consumer;

import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.boot.Policy;
import io.javanatic.harness.kernel.config.ConfigRowSpec;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.ToolSchema;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.tools.ToolArgs;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionContext;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;

import java.nio.file.Path;
import java.util.List;

/**
 * 治理自证（keyless，不调用模型）：compose（组合）→ dump（有效行摘要）→
 * boot（装配 + STANDARD 档校验，fail loud）→ 自有工具在注册面可见 → 直接执行一次。
 *
 * <p>只用 0.1.0 起可用的交集面（{@code compose}/{@code dump}/{@code boot}/{@code Plugin}/
 * {@code ToolDefinition.of}/{@code register}）。0.2.0 升级项：{@code AppBoot.bootReported}
 * （组合自述计数）与 {@code ToolRegistry.definitions}（含审批声明的完整定义）。
 *
 * @param args profile YAML 路径；缺省 {@code profile/consumer-sample.yml}。可选第二参为校验档名
 *             （{@code STANDARD}/{@code PRODUCTION}，缺省 {@code STANDARD}）——负例④用它把
 *             PRODUCTION 档撞上 AUTO 审批，钉住 {@code VerifyFailedException} 面
 */
public final class SelfCheck {

    public static void main(String[] args) throws Exception {
        Path profile = Path.of(args.length > 0 ? args[0] : "profile/consumer-sample.yml");
        Policy policy = args.length > 1 ? Policy.valueOf(args[1].toUpperCase()) : Policy.STANDARD;
        AppBoot.BootOptions options =
            new AppBoot.BootOptions(profile, List.of(), true, policy);

        List<ConfigRowSpec> composed = AppBoot.compose(options);
        List<ConfigRowSpec> enabled = AppBoot.resolve(composed);
        System.out.println("profile=" + profile);
        System.out.println("rows.composed=" + composed.size() + " rows.enabled=" + enabled.size());
        System.out.println("-- effective rows --");
        System.out.print(AppBoot.dump(enabled));
        System.out.println("-- end effective rows --");

        try (Runtime runtime = AppBoot.boot(options)) {
            Scope root = runtime.root();
            ToolRegistry registry = root.require(ToolRegistry.KEY);
            List<ToolSchema> schemas = registry.schemas(root);
            boolean visible = schemas.stream().anyMatch(schema -> schema.name().equals("word_count"));
            System.out.println("tool.schemas=" + schemas.size() + " word_count.visible=" + visible);

            ToolDefinition tool = registry.resolve(root, "word_count")
                .orElseThrow(() -> new IllegalStateException("word_count not registered"));
            ToolExecutionResult result = tool.tool().execute(
                ToolArgs.parse("{\"text\":\"governance is data\"}", tool.parameters()),
                new ToolExecutionContext(AbortSignal.never(),
                    Session.create(Session.newId("consumer-sample-self-check"), null, null)));
            System.out.println("word_count.invoke.error=" + result.isError()
                + " content=" + result.content());

            if (!visible || result.isError()) {
                System.err.println("SELF-CHECK FAILED");
                System.exit(1);
            }
        }
        System.out.println("SELF-CHECK OK");
    }
}
