package io.github.retreatisadvance.sample.consumer;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ValueSchema;

import java.util.Map;
import java.util.Set;

/**
 * 外部插件：把自有工具注册进 {@link ToolRegistry}。
 *
 * <p>发现路径与仓内插件相同——classpath 资源
 * {@code META-INF/services/io.javanatic.harness.kernel.plugin.Plugin}（JPMS module-path
 * 消费方改在自有 module-info 写 {@code provides}）；注册凭据挂自身 scope，
 * scope 关闭时随关停回收。
 */
public final class WordCountPlugin implements Plugin {

    private static final ValueSchema.Str TEXT = new ValueSchema.Str("Text to count words in");

    @Override
    public String id() {
        return "consumer-sample";
    }

    @Override
    public Set<String> requires() {
        return Set.of("tools");
    }

    @Override
    public void apply(Scope scope) {
        ToolRegistry registry = scope.require(ToolRegistry.KEY);
        ToolDefinition tool = ToolDefinition.of(
            "word_count",
            "Count whitespace-separated words in a text.",
            new ValueSchema.Object("Arguments", Map.of("text", TEXT)),
            (args, context) -> ToolExecutionResult.success("words=" + countWords(args.readString("text"))));
        scope.onClose(registry.register(scope, tool));
    }

    static int countWords(String text) {
        String trimmed = text.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }
}
