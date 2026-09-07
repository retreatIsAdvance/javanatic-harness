package io.javanatic.harness.session.persistence.jsonl;

import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionEvents;
import io.javanatic.harness.session.SessionHeader;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.event.ExtensionEvent;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionCodecRegistry;
import io.javanatic.harness.session.persistence.SessionEventCodec;
import io.javanatic.harness.session.persistence.SessionPersistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSONL 后端:订阅 session 域事件(CREATED 开写、APPENDED 增量、DISPOSED 收笔)。
 * 布局:{@code <root>/<sessionId>/header.json + log.jsonl};行 = 信封
 * {@code {"seq":N,"type":"...","ignorable":b,"data":{...}}}。写侧无 codec 的
 * 类型 fail loud;读侧未知 type 按 ignorable 跳过或拒绝。单进程追加
 * (多进程锁挂账 persistence 后续)。写入是同步逐事件的——SessionStore 的
 * APPENDED 派发为 notifyOrdered 保序。
 */
public final class JsonlPersistence implements SessionPersistence {

    private final Path root;
    private final SessionCodecRegistry codecs;
    private final Map<Id<Session>, SessionWriter> writers = new ConcurrentHashMap<>();

    JsonlPersistence(Path root, SessionCodecRegistry codecs) {
        this.root = root;
        this.codecs = codecs;
    }

    /**
     * 接线四个 session 域事件(挂 owner scope,R3)。
     *
     * @param owner 持久化插件 scope
     * @return 注销凭据
     */
    public Disposable attach(Scope owner) {
        List<Disposable> handles = new ArrayList<>();
        handles.add(owner.events().onGlobal(SessionEvents.CREATED, (carrier, session) ->
            writer(session).backfill(session)));
        handles.add(owner.events().onGlobal(SessionEvents.APPENDED, (carrier, entry) ->
            writer((Session) carrier).append((LoggedEvent<?>) entry)));
        handles.add(owner.events().onGlobal(SessionEvents.DISPOSED, (carrier, session) ->
            writers.remove(session.id())));
        handles.add(owner.events().onGlobal(SessionEvents.FLUSH, (carrier, session) ->
            writer(session).flushBarrier()));
        return Disposable.of(() -> handles.forEach(Disposable::close));
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public void save(Session session) throws IOException {
        writer(session).rewrite(session);
    }

    @Override
    public Loaded load(Id<Session> id) throws IOException {
        Path dir = root.resolve(id.value());
        Path headerFile = dir.resolve("header.json");
        if (!Files.isRegularFile(headerFile)) {
            throw new NoSuchElementException("session not on disk: " + id.value());
        }
        SessionHeader header = HeaderCodec.read(JacksonBridge.read(Files.readString(headerFile)));
        List<SessionEvent> events = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("log.jsonl"))) {
            if (line.isBlank()) {
                continue;
            }
            JsonValue.Obj envelope = JacksonBridge.read(line);
            long seq = envelope.get("seq").asLong();
            if (seq != events.size()) {
                throw new IllegalStateException("seq broken at " + seq + " (expected "
                    + events.size() + ") in session " + id.value());
            }
            String type = envelope.get("type").asString();
            SessionEventCodec<?> codec = codecs.forType(type).orElse(null);
            if (codec == null) {
                if (envelope.get("ignorable").asBool()) {
                    events.add(new ExtensionEvent() {
                        @Override public long time() {
                            return envelope.get("data").get("time").asLong();
                        }

                        @Override public String type() {
                            return type;
                        }
                    });
                    continue;
                }
                throw new IllegalStateException("unknown non-ignorable event type in log: " + type);
            }
            events.add(decode(codec, envelope.get("data")));
        }
        return new Loaded(header, events);
    }

    @SuppressWarnings("unchecked")
    private <T extends SessionEvent> T decode(SessionEventCodec<T> codec, JsonValue data) {
        return codec.read((JsonValue.Obj) data);
    }

    private SessionWriter writer(Session session) {
        return writers.computeIfAbsent(session.id(), id -> new SessionWriter(root.resolve(id.value()), codecs));
    }

    /** 单会话写入器:逐行追加;断点续写(文件行数即已写 seq+1)。 */
    private static final class SessionWriter {
        private final Path dir;
        private final SessionCodecRegistry codecs;
        private long writtenLines = -1;

        SessionWriter(Path dir, SessionCodecRegistry codecs) {
            this.dir = dir;
            this.codecs = codecs;
        }

        void backfill(Session session) throws IOException {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("header.json"),
                JacksonBridge.write(HeaderCodec.write(session.header())));
            rewrite(session);
        }

        synchronized void rewrite(Session session) throws IOException {
            Path log = dir.resolve("log.jsonl");
            writtenLines = Files.isRegularFile(log) ? Files.lines(log).count() : 0;
            for (LoggedEvent<? extends SessionEvent> entry : session.events()) {
                if (entry.seq() >= writtenLines) {
                    writeEnvelope(entry);
                }
            }
        }

        synchronized void append(LoggedEvent<?> entry) throws IOException {
            if (writtenLines < 0) {
                throw new IllegalStateException("writer not backfilled (no CREATED seen)");
            }
            writeEnvelope(entry);
        }

        void flushBarrier() {
            // 同步逐事件写,无缓冲积压——barrier 挂点保留给异步后端
        }

        private void writeEnvelope(LoggedEvent<?> entry) throws IOException {
            SessionEvent event = entry.event();
            SessionEventCodec<SessionEvent> codec =
                (SessionEventCodec<SessionEvent>) codecs.forType(event.type())
                    .orElseThrow(() -> new IllegalStateException(
                        "no codec registered for type: " + event.type()));
            JsonValue.Obj envelope = JsonValue.object()
                .set("seq", entry.seq())
                .set("type", event.type())
                .set("ignorable", event.ignorable())
                .set("data", codec.write(event))
                .build();
            Files.writeString(dir.resolve("log.jsonl"), JacksonBridge.write(envelope) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            writtenLines = entry.seq() + 1;
        }
    }

    /** header 编解码(盘上 5 字段;parent 为 null 时省略)。 */
    private static final class HeaderCodec {
        static JsonValue.Obj write(SessionHeader header) {
            JsonValue.Builder builder = JsonValue.object()
                .set("version", header.version())
                .set("id", header.id().value())
                .set("createdAt", header.createdAt())
                .set("seedLength", header.seedLength());
            if (header.parentSession() != null) {
                builder.set("parentSession", header.parentSession().value());
            }
            return builder.build();
        }

        static SessionHeader read(JsonValue.Obj obj) {
            return new SessionHeader((int) obj.get("version").asLong(),
                Session.newId(obj.get("id").asString()), obj.get("createdAt").asLong(),
                obj.get("parentSession").asString() == null ? null
                    : Session.newId(obj.get("parentSession").asString()),
                (int) obj.get("seedLength").asLong());
        }
    }
}
