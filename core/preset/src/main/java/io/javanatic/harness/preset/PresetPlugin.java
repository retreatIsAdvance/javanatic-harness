package io.javanatic.harness.preset;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 提供 AgentPresets 服务(id "presets")。root 经构造器或 config 给出;
 * 缺省 {@code ~/.harness/presets}(preset 位置不是安全边界,给文档化默认)。
 */
public final class PresetPlugin implements Plugin {

    /** 数据组合路径的文档化默认。 */
    public static final String DEFAULT_ROOT_SUFFIX = ".harness/presets";

    private final Path explicitRoot;

    /** 数据组合路径:root 从行配置解析(缺省 {@code ~/.harness/presets})。 */
    public PresetPlugin() {
        this.explicitRoot = null;
    }

    /** @param root preset 根目录(程序化组合的显式选择) */
    public PresetPlugin(Path root) {
        this.explicitRoot = Objects.requireNonNull(root, "root");
    }

    @Override
    public String id() {
        return "presets";
    }

    @Override
    public void apply(Scope scope) {
        Path root = explicitRoot;
        if (root == null) {
            String configured = ConfigValues.stringValue(
                scope.require(ConfigService.KEY).configFor(id()), id(), "root", null);
            root = configured != null ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), DEFAULT_ROOT_SUFFIX);
        }
        scope.provide(AgentPresets.KEY, new PresetService(root));
    }

}
