package io.javanatic.harness.llm.replay;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.LlmService;
import io.javanatic.harness.llm.StreamChunk;

import java.util.List;
import java.util.Set;

/**
 * 回放 Provider（id "llm-replay"）：requires "llm"，apply 时注册
 * {@code "replay"} adapter，注册凭据挂自身 scope（插件卸载即注销，R3）。
 */
public final class ReplayPlugin implements Plugin {

    private final List<List<StreamChunk>> scripts;

    /** @param scripts 传给 {@link ReplayAdapter} 的逐次脚本 */
    public ReplayPlugin(List<List<StreamChunk>> scripts) {
        this.scripts = List.copyOf(scripts);
    }

    @Override
    public String id() {
        return "llm-replay";
    }

    @Override
    public Set<String> requires() {
        return Set.of("llm");
    }

    @Override
    public void apply(Scope scope) {
        LlmService llm = scope.require(LlmService.KEY);
        scope.onClose(llm.registerAdapter("replay", new ReplayAdapter(scripts)));
    }
}
