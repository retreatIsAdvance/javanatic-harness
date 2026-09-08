package io.javanatic.harness.kernel.config;

import io.javanatic.harness.kernel.scope.ServiceKey;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 组合清单（R1 三规则之一，03 §8）：boot 从最终行序 + 已解析 config 生成并注册；
 * session-store 创建 SessionHeader 时消费。清单 + 事件日志 = 可重建性的全部
 * 持久化事实。插件版本钉扎随发布切片。
 *
 * @param rows 最终组合行（禁用行不进清单——它们不参与组装）
 */
public record CompositionManifest(List<Row> rows) {

    /** 本服务的服务键（boot 提供）。 */
    public static final ServiceKey<CompositionManifest> KEY = new ServiceKey<>("composition-manifest");

    /** @throws NullPointerException rows 为 null 时 */
    public CompositionManifest {
        Objects.requireNonNull(rows, "rows");
        rows = List.copyOf(rows);
    }

    /** 一行组合的清单条目。 */
    public record Row(String plugin, Map<String, Object> config) {

        /** @throws NullPointerException plugin 为 null 时 */
        public Row {
            Objects.requireNonNull(plugin, "plugin");
            config = config == null ? Map.of() : Map.copyOf(config);
        }
    }
}
