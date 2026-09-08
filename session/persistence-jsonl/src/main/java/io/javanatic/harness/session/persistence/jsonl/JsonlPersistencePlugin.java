package io.javanatic.harness.session.persistence.jsonl;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.session.persistence.SessionCodecRegistry;
import io.javanatic.harness.session.persistence.SessionPersistence;

import java.nio.file.Path;
import java.util.Set;

/**
 * 提供 codec 注册表(预置核心 10 codec)与 JSONL 持久化服务
 * (id "persistence-jsonl",requires "session-store")。目录根组合期注入。
 */
public final class JsonlPersistencePlugin implements Plugin {

    private final Path explicitRoot;

    /** 数据组合路径：root 从行配置解析——持久化位置无默认，缺失 fail loud。 */
    public JsonlPersistencePlugin() {
        this.explicitRoot = null;
    }

    /** @param root 会话存储根目录(如 ~/.harness/sessions;程序化组合的显式选择) */
    public JsonlPersistencePlugin(Path root) {
        this.explicitRoot = root;
    }

    private Path root(Scope scope) {
        if (explicitRoot != null) {
            return explicitRoot;
        }
        return Path.of(ConfigValues.requireString(
            scope.require(ConfigService.KEY).configFor(id()), id(), "root"));
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
        JsonlPersistence persistence = new JsonlPersistence(root(scope), codecs);
        scope.provide(SessionPersistence.KEY, persistence);
        scope.onClose(persistence.attach(scope));
    }
}
