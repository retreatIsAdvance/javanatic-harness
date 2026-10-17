package io.javanatic.harness.mcp;

import java.util.Objects;

/**
 * 远端工具描述（tools/list 快照条目，it27）：schema 为 wire 原文（String）——
 * JSON 解析在边界模块（transport 组帧与消费面映射各自持有，同 core/tools
 * 信任边界先例）；映射到 {@code ValueSchema}（白名单）由消费面做（05 schema 边界）。
 *
 * @param serverId 来源 server id
 * @param name raw 工具名（拒含 "__"：配置校验 + tools/list 结果校验两处）
 * @param description 工具描述（远端原文；可能为空串）
 * @param inputSchemaJson 参数 JSON Schema 原文（对象文本）
 */
public record McpToolDescriptor(String serverId, String name, String description, String inputSchemaJson) {

    /** @throws NullPointerException 任一字段为 null 时 */
    public McpToolDescriptor {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchemaJson, "inputSchemaJson");
    }
}
