package io.javanatic.harness.systemprompt;

import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.session.Session;

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
        if (sections.isEmpty()) {
            return "";
        }
        List<PromptSection> ordered = new ArrayList<>(sections);
        ordered.sort(Comparator.comparingInt(PromptSection::priority));
        return String.join("\n\n", ordered.stream().map(PromptSection::content).toList());
    }
}
