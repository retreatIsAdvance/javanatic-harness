package io.javanatic.harness.session.persistence.jsonl;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.session.persistence.SessionCodecRegistry;
import io.javanatic.harness.session.persistence.SessionPersistence;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * 提供 codec 注册表(预置核心 10 codec)与 JSONL 持久化服务
 * (id "persistence-jsonl",requires "session-store")。目录根组合期注入。
 */
public final class JsonlPersistencePlugin implements Plugin {

    private final Path root;

    /** @param root 会话存储根目录(如 ~/.harness/sessions) */
    public JsonlPersistencePlugin(Path root) {
        this.root = Objects.requireNonNull(root, "root");
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("root must be absolute: " + root);
        }
    }

    @Override
    public String id() {
        return "persistence-jsonl";
    }

    @Override
    public Set<String> requires() {
        return Set.of("session-store");
    }

    @Override
    public void apply(Scope scope) {
        SessionCodecRegistry codecs = new SessionCodecRegistry();
        CoreCodecs.registerAll(codecs, scope);
        scope.provide(SessionCodecRegistry.KEY, codecs);
        JsonlPersistence persistence = new JsonlPersistence(root, codecs);
        scope.provide(SessionPersistence.KEY, persistence);
        scope.onClose(persistence.attach(scope));
    }
}
