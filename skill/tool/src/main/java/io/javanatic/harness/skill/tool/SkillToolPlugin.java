package io.javanatic.harness.skill.tool;

import io.javanatic.harness.agentloop.AgentEvents;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.skill.SkillDefinition;
import io.javanatic.harness.skill.SkillService;
import io.javanatic.harness.systemprompt.PromptSection;
import io.javanatic.harness.systemprompt.SystemPromptService;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ValueSchema;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 技能消费插件（id "skill-tool"，requires "tools" + "system-prompt" + "skill"，
 * it26 内容 2/3）：skill(name) 装载工具（纯读文本，无新执行通道；of() 需审批 =
 * T2）+ 轮首目录装载（PRE_STEP 挂点、agent-loop 零改动）→ skill/catalog 事件
 * 落账（变更才落、无技能零噪声、清空落空事件退役）→ Dynamic 提示段读最新
 * 事件（it21 ProjectInstructions / plan:policy 同款：目录事实在日志里，回放
 * 零磁盘依赖，R1）。描述截断上限暂为常量缺省 500（dsh 同名口径）；配置键
 * 接线随停 3 bundle 行落（07 同步）。
 */
public final class SkillToolPlugin implements Plugin {

    /** 装载工具名（目录稳定）。 */
    public static final String SKILL_TOOL = "skill";

    /** 目录段排序键：随 plan:policy（50）之后——先治理指导、后能力目录。 */
    static final int SECTION_ORDER = 60;

    /** 描述截断上限（事件条目与目录段共用；缺省 = dsh 同名口径 500）。 */
    static final int DESCRIPTION_LIMIT = 500;

    private static final String TOOL_DESCRIPTION =
        "Load the full instructions of a skill by name. Call this before acting on any skill "
        + "listed in your available-skills catalog; never act on the catalog summary alone.";

    private static final String SECTION_HEADER =
        "可用技能（先装载再行动：用 skill 工具装载全文后再按正文行动，不得仅凭摘要推断指令）：";

    @Override
    public String id() {
        return "skill-tool";
    }

    @Override
    public Set<String> requires() {
        return Set.of("tools", "system-prompt", "skill");
    }

    @Override
    public void apply(Scope scope) {
        SkillService skills = scope.require(SkillService.KEY);
        ToolRegistry registry = scope.require(ToolRegistry.KEY);
        scope.onClose(registry.register(scope, loadTool(skills)));
        SystemPromptService prompts = scope.require(SystemPromptService.KEY);
        scope.onClose(prompts.register(new PromptSection.Dynamic(SECTION_ORDER,
            session -> section(session))));
        // 轮首目录装载：CREATED 时拿到 session，再在该 agent scope 挂 PRE_STEP
        // 直通监听（副作用只是落账；裁决原样放行）。内层订阅绑定 agent scope，
        // 随其关闭回收（R3）。
        scope.onClose(scope.events().on(AgentEvents.CREATED, (carrier, agent) ->
            agent.scope().events().onWaterfall(AgentEvents.PRE_STEP, (c, args) -> {
                refresh(agent.session(), skills);
                return args.next();
            })));
    }

    /** 装载工具：正文 + 来源头；未知名报错（不在目录或已退役）。 */
    private static ToolDefinition loadTool(SkillService skills) {
        return ToolDefinition.of(SKILL_TOOL, TOOL_DESCRIPTION,
            new ValueSchema.Object("参数", Map.of(
                "name", new ValueSchema.Str("Skill name from the available-skills catalog"))),
            (args, context) -> {
                String name = args.readString("name");
                return skills.load(name)
                    .map(SkillToolPlugin::render)
                    .map(ToolExecutionResult::success)
                    .orElseGet(() -> ToolExecutionResult.error(
                        "skill not found: '" + name + "' (not in catalog or retired)"));
            });
    }

    private static String render(SkillDefinition definition) {
        return "[skill: " + definition.name() + " | source: " + definition.summary().source() + "]\n\n"
            + definition.body();
    }

    /** 目录快照 → 截断条目；与最新事件比对，变更才落账（含空事件退役）。 */
    static void refresh(Session session, SkillService skills) {
        List<SkillCatalogEvent.Entry> entries = skills.catalog().skills().stream()
            .map(s -> new SkillCatalogEvent.Entry(s.name(), truncate(s.description()), s.source()))
            .toList();
        Optional<SkillCatalogEvent> latest = latest(session);
        if (latest.isPresent() && latest.get().entries().equals(entries)) {
            return;
        }
        if (latest.isEmpty() && entries.isEmpty()) {
            return; // 无技能零噪声：从未有过就不落事件、目录段不出现
        }
        session.append(new SkillCatalogEvent(System.currentTimeMillis(), entries));
    }

    /** 动态目录段：读最新事件（只读日志，同日志必同提示词，R1）；无技能为空串。 */
    static String section(Session session) {
        Optional<SkillCatalogEvent> latest = latest(session);
        if (latest.isEmpty() || latest.get().entries().isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder(SECTION_HEADER);
        for (SkillCatalogEvent.Entry entry : latest.get().entries()) {
            text.append("\n- ").append(entry.name())
                .append(" — ").append(entry.description())
                .append("（来源: ").append(entry.source()).append("）");
        }
        return text.toString();
    }

    private static Optional<SkillCatalogEvent> latest(Session session) {
        return session.events().stream()
            .map(LoggedEvent::event)
            .filter(SkillCatalogEvent.class::isInstance)
            .map(SkillCatalogEvent.class::cast)
            .reduce((first, second) -> second);
    }

    private static String truncate(String description) {
        return description.length() <= DESCRIPTION_LIMIT
            ? description
            : description.substring(0, DESCRIPTION_LIMIT);
    }
}
