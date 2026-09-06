package io.javanatic.harness.session.persistence;

import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.ServiceKey;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * codec 注册表服务(type → codec;重复/不一致 fail loud)。注册即 effect:
 * 经插件 scope 回收(R3)。写侧遇到无 codec 的事件类型时 fail loud——
 * 序列化能力属于持久化边界,Session.append 不感知。
 */
public final class SessionCodecRegistry {

    /** 本服务的服务键。 */
    public static final ServiceKey<SessionCodecRegistry> KEY = new ServiceKey<>("session-codecs");

    private final Map<String, SessionEventCodec<?>> byType = new ConcurrentHashMap<>();

    /**
     * 注册一个 codec。
     *
     * @param codec 事件 codec
     * @throws IllegalStateException type 重复或 type()/typeClass() 的 type() 不一致时
     * @return 注销凭据
     */
    public Disposable register(SessionEventCodec<?> codec) {
        Objects.requireNonNull(codec, "codec");
        String type = codec.type();
        SessionEventCodec<?> existing = byType.putIfAbsent(type, codec);
        if (existing != null) {
            throw new IllegalStateException("codec already registered for type: " + type);
        }
        return Disposable.of(() -> byType.remove(type, codec));
    }

    /** @return 该类型的 codec */
    public Optional<SessionEventCodec<?>> forType(String type) {
        return Optional.ofNullable(byType.get(type));
    }
}
