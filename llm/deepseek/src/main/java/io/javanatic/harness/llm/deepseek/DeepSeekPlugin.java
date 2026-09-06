package io.javanatic.harness.llm.deepseek;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.LlmService;

import java.util.Objects;
import java.util.Set;

/**
 * DeepSeek Provider（id "llm-deepseek"，requires "llm"）。配置经构造器注入
 * （组合期显式选择；ConfigService/CredentialsService 随组合切片接手来源）。
 */
public final class DeepSeekPlugin implements Plugin {

    private final DeepSeekAdapter adapter;

    /** @param options provider 选项（key 由组合处从环境变量读取） */
    public DeepSeekPlugin(DeepSeekOptions options) {
        Objects.requireNonNull(options, "options");
        this.adapter = new DeepSeekAdapter(options);
    }

    @Override
    public String id() {
        return "llm-deepseek";
    }

    @Override
    public Set<String> requires() {
        return Set.of("llm");
    }

    @Override
    public void apply(Scope scope) {
        LlmService llm = scope.require(LlmService.KEY);
        scope.onClose(llm.registerAdapter("deepseek", adapter));
    }
}
