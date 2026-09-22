package io.javanatic.harness.systemprompt;

import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.ProjectInstructions;
import io.javanatic.harness.session.event.RequestHeader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** 默认实现：注册表 + 确定性拼接（priority 稳定排序，等序保持注册序）。 */
final class SystemPromptImpl implements SystemPromptService {

    private final List<PromptSection> sections = new CopyOnWriteArrayList<>();

    @Override
    public Disposable register(PromptSection section) {
        Objects.requireNonNull(section, "section");
        sections.add(section);
        return Disposable.of(() -> sections.remove(section));
    }

    @Override
    public String assemble(Session session) {
        Objects.requireNonNull(session, "session");
        List<String> parts = new ArrayList<>();
        String context = contextSection(session);
        if (!context.isEmpty()) {
            parts.add(context);
        }
        String instructions = instructionsSection(session);
        if (!instructions.isEmpty()) {
            parts.add(instructions);
        }
        List<PromptSection> ordered = new ArrayList<>(sections);
        ordered.sort(Comparator.comparingInt(PromptSection::priority));
        for (PromptSection section : ordered) {
            // 动态段未激活产出空串（如 plan:policy 未激活）——跳过，不产生空段落
            String text = switch (section) {
                case PromptSection.Static s -> s.content();
                case PromptSection.Dynamic d -> d.content().apply(session);
            };
            if (!text.isEmpty()) {
                parts.add(text);
            }
        }
        return String.join("\n\n", parts);
    }

    /**
     * 说明 section:最新 project/instructions 事件的内容(it21,R1:内容落账在事件里,
     * 同日志必同提示词;无事件返回空串)。段首给出来源路径,截断以尾部标记标明。
     */
    private static String instructionsSection(Session session) {
        return session.events().stream()
            .map(entry -> entry.event())
            .filter(ProjectInstructions.class::isInstance)
            .map(ProjectInstructions.class::cast)
            .reduce((first, second) -> second)   // 最新
            .map(event -> {
                String text = "Project instructions (" + event.path() + "):\n" + event.content();
                return event.truncated() ? text + "\n… (truncated)" : text;
            })
            .orElse("");
    }

    /**
     * 上下文 section:最新 request/header 事件的 cwd/date(R1:值落账在事件里,
     * 同日志必同提示词;无事件返回空串——行为与 it5 前完全一致)。
     */
    private static String contextSection(Session session) {
        return session.events().stream()
            .map(entry -> entry.event())
            .filter(RequestHeader.class::isInstance)
            .map(event -> (RequestHeader) event)
            .reduce((first, second) -> second)   // 最新
            .map(header -> {
                StringBuilder sb = new StringBuilder("Current context:");
                if (header.cwd() != null && !header.cwd().isEmpty()) {
                    sb.append("\n- working directory: ").append(header.cwd());
                }
                if (header.date() != null && !header.date().isEmpty()) {
                    sb.append("\n- date: ").append(header.date());
                }
                return sb.toString();
            })
            .orElse("");
    }
}
