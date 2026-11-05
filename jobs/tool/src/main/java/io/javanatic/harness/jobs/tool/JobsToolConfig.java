package io.javanatic.harness.jobs.tool;

import io.javanatic.harness.kernel.config.ConfigValues;
import java.util.Map;

/**
 * jobs-tool 行配置（B31）：单键 {@code maxReadBytes}——job_read 单次返回的
 * 字节上限（正整数，缺省 262144 = 256 KiB，B-MCP-RESULT 同量纲）；游标语义
 * 使上限只影响单次切片不影响可读总量。未知键 / 非正数 fail loud。
 */
public record JobsToolConfig(long maxReadBytes) {

    /** 缺省上限（256 KiB）。 */
    static final long DEFAULT_MAX_READ_BYTES = 262144L;

    /** @param maxReadBytes 单次读取字节上限（正数） */
    public JobsToolConfig {
        if (maxReadBytes <= 0) {
            throw new IllegalArgumentException("maxReadBytes must be positive");
        }
    }

    static JobsToolConfig defaults() {
        return new JobsToolConfig(DEFAULT_MAX_READ_BYTES);
    }

    static JobsToolConfig parse(Map<String, Object> config, String pluginId) {
        return new JobsToolConfig(ConfigValues.longValue(config, pluginId,
                "maxReadBytes", DEFAULT_MAX_READ_BYTES));
    }
}
