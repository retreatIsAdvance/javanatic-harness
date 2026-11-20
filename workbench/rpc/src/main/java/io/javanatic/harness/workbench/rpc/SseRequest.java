package io.javanatic.harness.workbench.rpc;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.Session;

import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 仅 SSE 路由使用的严格 query/header 边界；失败统一翻译为具名 400。 */
record SseRequest(Id<Session> session, long afterSeq) {
    static SseRequest parse(String rawQuery, List<String> lastEventIds) {
        Map<String, String> values = new HashMap<>();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&", -1)) {
                String[] parts = pair.split("=", 2);
                if (parts.length != 2 || values.putIfAbsent(decode(parts[0]), decode(parts[1])) != null) {
                    throw invalid("duplicate or malformed query");
                }
            }
        }
        String session = values.get("session");
        if (session == null || session.isBlank() || session.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("session");
        }
        if (lastEventIds.size() > 1) {
            throw invalid("multiple Last-Event-ID headers");
        }
        String cursor = lastEventIds.isEmpty() ? values.get("lastSeq") : lastEventIds.getFirst();
        long after = -1;
        if (cursor != null) {
            if (!cursor.matches("-?[0-9]+")) {
                throw invalid("cursor");
            }
            try {
                after = Long.parseLong(cursor);
            } catch (NumberFormatException e) {
                throw invalid("cursor out of range");
            }
            if (after < -1) {
                throw invalid("cursor below -1");
            }
        }
        return new SseRequest(new Id<>(session), after);
    }

    private static String decode(String raw) {
        try {
            // URLDecoder 的 UTF-8 缺省会替换坏字节；先逐字节解码，再用 REPORT 校验 UTF-8。
            String rawBytes = new String(raw.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
            String bytes = URLDecoder.decode(rawBytes, StandardCharsets.ISO_8859_1);
            return StandardCharsets.UTF_8.newDecoder().decode(
                ByteBuffer.wrap(bytes.getBytes(StandardCharsets.ISO_8859_1))).toString();
        } catch (IllegalArgumentException | CharacterCodingException e) {
            throw invalid("URL encoding");
        }
    }

    private static SseEventSource.HttpFailureException invalid(String reason) {
        return new SseEventSource.HttpFailureException(400, "invalid SSE " + reason);
    }
}
