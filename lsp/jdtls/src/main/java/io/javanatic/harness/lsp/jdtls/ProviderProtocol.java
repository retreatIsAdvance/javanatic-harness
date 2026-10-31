package io.javanatic.harness.lsp.jdtls;

import com.fasterxml.jackson.databind.JsonNode;

import io.javanatic.harness.lsp.LspDiagnostic;
import io.javanatic.harness.lsp.LspErrorCode;
import io.javanatic.harness.lsp.LspException;
import io.javanatic.harness.lsp.LspLocation;
import io.javanatic.harness.lsp.LspPosition;
import io.javanatic.harness.lsp.LspRange;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Provider 的严格 JSON 值边界；任何坏条目使整批失败，不能过滤成成功空值。 */
final class ProviderProtocol {

    record Publication(URI uri, OptionalInt version, List<LspDiagnostic> items) {
        Publication {
            items = List.copyOf(items);
        }
    }

    private ProviderProtocol() {
    }

    static Publication publication(JsonNode params) {
        try {
            requireObject(params, "publishDiagnostics params");
            URI uri = uri(params.get("uri"));
            OptionalInt version = params.has("version")
                ? OptionalInt.of(integer(params.get("version"))) : OptionalInt.empty();
            JsonNode diagnostics = params.get("diagnostics");
            if (diagnostics == null || !diagnostics.isArray()) {
                throw malformed("publishDiagnostics requires diagnostics array");
            }
            List<LspDiagnostic> items = new ArrayList<>();
            for (JsonNode diagnostic : diagnostics) {
                items.add(diagnostic(diagnostic));
            }
            return new Publication(uri, version, items);
        } catch (IllegalArgumentException e) {
            throw malformed(e.getMessage());
        }
    }

    private static LspDiagnostic diagnostic(JsonNode node) {
        requireObject(node, "diagnostic");
        LspRange range = range(node.get("range"));
        LspDiagnostic.Severity severity = node.has("severity") ? switch (integer(node.get("severity"))) {
            case 1 -> LspDiagnostic.Severity.ERROR;
            case 2 -> LspDiagnostic.Severity.WARNING;
            case 3 -> LspDiagnostic.Severity.INFORMATION;
            case 4 -> LspDiagnostic.Severity.HINT;
            default -> throw malformed("invalid diagnostic severity");
        } : LspDiagnostic.Severity.UNSPECIFIED;
        Optional<String> code = Optional.empty();
        if (node.has("code")) {
            JsonNode value = node.get("code");
            code = Optional.of(value.isTextual() ? value.textValue() : Integer.toString(integer(value)));
        }
        Optional<String> source = node.has("source") ? Optional.of(text(node.get("source"))) : Optional.empty();
        return new LspDiagnostic(range, severity, code, source, text(node.get("message")));
    }

    /** 保持既有 Location / LocationLink 映射范围，不新增定位协议形状。 */
    static List<LspLocation> locations(JsonNode result) {
        if (result == null || result.isNull()) {
            return List.of();
        }
        try {
            List<LspLocation> locations = new ArrayList<>();
            if (result.isArray()) {
                for (JsonNode node : result) {
                    locations.add(location(node));
                }
            } else {
                locations.add(location(result));
            }
            return List.copyOf(locations);
        } catch (IllegalArgumentException e) {
            throw malformed(e.getMessage());
        }
    }

    private static LspLocation location(JsonNode node) {
        requireObject(node, "location");
        if (node.has("targetUri")) {
            range(node.get("targetSelectionRange"));
            if (node.has("originSelectionRange")) {
                range(node.get("originSelectionRange"));
            }
            return new LspLocation(uri(node.get("targetUri")), range(node.get("targetRange")));
        }
        return new LspLocation(uri(node.get("uri")), range(node.get("range")));
    }

    private static LspRange range(JsonNode node) {
        requireObject(node, "range");
        return new LspRange(position(node.get("start")), position(node.get("end")));
    }

    private static LspPosition position(JsonNode node) {
        requireObject(node, "position");
        return new LspPosition(integer(node.get("line")), integer(node.get("character")));
    }

    private static int integer(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw malformed("expected signed LSP integer");
        }
        return node.intValue();
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw malformed("expected string");
        }
        return node.textValue();
    }

    private static URI uri(JsonNode node) {
        URI uri = URI.create(text(node));
        if (!uri.isAbsolute() || uri.isOpaque()) {
            throw malformed("expected absolute hierarchical URI");
        }
        return uri;
    }

    private static void requireObject(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            throw malformed(name + " must be an object");
        }
    }

    private static LspException malformed(String detail) {
        return new LspException(LspErrorCode.PROTOCOL, "malformed LSP value: " + detail);
    }
}
