package io.javanatic.harness.examples.agent.spine;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentPlugin;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.agentloop.AgentLoopPlugin;
import io.javanatic.harness.agentloop.LoopGuard;
import io.javanatic.harness.agentloop.LoopGuardPlugin;
import io.javanatic.harness.fs.local.FsLocalPlugin;
import io.javanatic.harness.fs.tool.FsToolPlugin;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.LlmPlugin;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.llm.replay.ReplayPlugin;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.LlmRequestEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.StepEnd;
import io.javanatic.harness.session.event.StepStart;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.event.UserMessageEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.systemprompt.PromptSection;
import io.javanatic.harness.systemprompt.SystemPromptPlugin;
import io.javanatic.harness.systemprompt.SystemPromptService;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.ToolsPlugin;

import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/**
 * 最小 agent 主干竖切（keyless）：一条 task 经 inbox → pre-step 落账 → step 状态机
 * 调 replay 模型 → fs_read 经 executor 真跑 → 结果回填 → 终答 → 关轮。
 * 运行：{@code mvn -B -q -pl examples/agent-spine -am package} 后
 * {@code java --module-path 本地模块目录 -m io.javanatic.harness.examples.agent.spine}。
 */
public final class SpineMain {

    private static final System.Logger LOG = System.getLogger(SpineMain.class.getName());

    private static final String NOTE = "JH spine demo note";

    public static void main(String[] args) throws Exception {
        Path workspace = Files.createTempDirectory("jh-spine");
        run(workspace).forEach(entry -> LOG.log(Level.INFO, summarize(entry)));
    }

    /**
     * 跑一遍完整竖切，返回最终事件日志（main 打印；测试断言同一事实）。
     *
     * @param workspace demo 工作目录（内写 note.txt）
     * @return 会话事件序列
     */
    static List<LoggedEvent<? extends SessionEvent>> run(Path workspace) throws Exception {
        Path note = workspace.resolve("note.txt");
        Files.writeString(note, NOTE);
        List<List<StreamChunk>> scripts = List.of(
            List.of(
                new StreamChunk.Delta("先读一下笔记。"),
                new StreamChunk.DeltaToolUse(CallId.of("c1"), "fs_read",
                    "{\"path\":\"" + note + "\"}"),
                new StreamChunk.Finish(FinishReason.TOOL_USE)),
            List.of(
                new StreamChunk.Delta("笔记内容是:" + NOTE),
                new StreamChunk.Finish(FinishReason.STOP)));

        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new AgentPlugin(),
                new LoopGuardPlugin(new LoopGuard.Limits(10, 10)),
                new SystemPromptPlugin(), new LlmPlugin(), new ReplayPlugin(scripts),
                new ApprovalAutoPlugin(), new ToolsPlugin(),
                new FsLocalPlugin(workspace), new FsToolPlugin(),
                new AgentLoopPlugin(Clock.systemUTC())));
            SystemPromptService prompts = rt.root().require(SystemPromptService.KEY);
            prompts.register(new PromptSection(0, "You are the JH agent-spine demo agent."));

            AgentRegistry agents = rt.root().require(AgentRegistry.KEY);
            AgentHandle handle = agents.create(rt.root(),
                CreateAgentOptions.of(Session.newId("spine-1"), new AgentOptions("replay", "spine-model")));
            Agent agent = handle.agent();
            agent.followup(UserMessage.of("读一下笔记并告诉我内容。", new MessageSource.User()));
            agent.whenIdle().join();
            List<LoggedEvent<? extends SessionEvent>> events = agent.session().events();
            handle.disposeAndAwait();
            return events;
        }
    }

    /** 事件的一行人读摘要（ExtensionEvent 走默认分支——可扩展联合的文档化默认）。 */
    private static String summarize(LoggedEvent<? extends SessionEvent> entry) {
        SessionEvent event = entry.event();
        return switch (event) {
            case TurnStart t -> "turn " + t.turn() + " 开始";
            case TurnEnd t -> "turn " + t.turn() + " 结束:" + t.reason();
            case StepStart s -> "  step " + s.step() + " 开始";
            case StepEnd s -> "  step " + s.step() + " 结束";
            case UserMessageEvent m -> "  user: " + m.message().content();
            case AssistantMessageEvent m -> "  assistant: " + m.message().content();
            case LlmRequestEvent r -> "  llm/request prompt-sha=" + r.systemPromptSha256().substring(0, 8);
            case ToolCallEvent c -> "  tool/call " + c.name() + " " + c.arguments();
            case ToolResultEvent r ->
                "  tool/result " + (r.block().isError() ? "ERROR " : "") + r.block().content();
            default -> "  " + event.type();
        };
    }
}
