package io.javanatic.harness.systemprompt;

import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.session.Session;
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
        String context = contextSection(session);
        if (sections.isEmpty()) {
            return context;
        }
        List<PromptSection> ordered = new ArrayList<>(sections);
        ordered.sort(Comparator.comparingInt(PromptSection::priority));
        String body = String.join("\n\n", ordered.stream().map(PromptSection::content).toList());
        return context.isEmpty() ? body : context + "\n\n" + body;
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
