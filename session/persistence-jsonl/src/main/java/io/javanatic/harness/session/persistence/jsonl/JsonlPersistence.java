package io.javanatic.harness.session.persistence.jsonl;

import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionEvents;
import io.javanatic.harness.session.SessionHeader;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.event.ExtensionEvent;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.kernel.config.CompositionManifest;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionCodecRegistry;
import io.javanatic.harness.session.persistence.SessionEventCodec;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.WriterLockException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
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
 * 类型 fail loud;读侧未知 type 按 ignorable 跳过或拒绝。**单写者保护**:
 * 会话目录级 {@code .writer.lock}({@code FileChannel.tryLock})由写者持有,
 * 第二写者(第二进程或同 JVM 第二实例)占用即 {@link WriterLockException}
 * fail loud——拒绝而非合并;{@code load} 对外来写者先试锁(占用即拒),
 * 本实例为写者时在写者 monitor 内修复+读。写入是同步逐事件的——SessionStore 的
 * APPENDED 派发为 notifyOrdered 保序。耐久:flush barrier = 对账(已写行数
 * 追平 session seq,不符即抛——写失败被 contained 吞后由此显形)+ fsync
 * ({@code FileChannel.force});管辖限于本实例 backfill 过的会话(无写者 =
 * 从未在此持久化,屏障跳过不假证);
 * load/续写打开时修复撕裂尾(末行无换行终止:
 * 可解析则补换行,否则截到最后完整行——丢失至多半行),内部行破损仍 fail loud。
 */
public final class JsonlPersistence implements SessionPersistence {

    private static final System.Logger LOG = System.getLogger(JsonlPersistence.class.getName());

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
        handles.add(owner.events().onGlobal(SessionEvents.DISPOSED, (carrier, session) -> {
            SessionWriter writer = writers.remove(session.id());
            if (writer != null) {
                writer.close(); // 收笔即释放写者锁(单写者保护的生命周期终点)
            }
        }));
        handles.add(owner.events().onGlobal(SessionEvents.FLUSH, (carrier, session) -> {
            SessionWriter writer = writers.get(session.id());
            if (writer == null) {
                // 本实例未持久化过此会话(直构/未经 store 创建):无写者即无「已耐久」
                // 主张可言——屏障对其无管辖(等价于无 listener),不假证也不误报
                LOG.log(System.Logger.Level.WARNING,
                    "flush barrier skipped for session {0}: no writer (never persisted here)",
                    session.id().value());
                return;
            }
            writer.flushBarrier(session.seq());
        }));
        return Disposable.of(() -> {
            handles.forEach(Disposable::close);
            // scope 收拢即释放全部写者锁(未走 DISPOSED 的会话也不留占用)
            writers.values().forEach(SessionWriter::close);
            writers.clear();
        });
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
        SessionWriter own = writers.get(id);
        if (own != null) {
            // 本实例就是写者:在自己的写者 monitor 内修复+读——不与 append 交错,
            // 也不去探测只会撞上自己的锁(单写者保护针对的是「另一个写者」)
            synchronized (own) {
                repairTornTail(dir.resolve("log.jsonl"));
                return readLoaded(dir, headerFile, id);
            }
        }
        // 外来写者占用检查先于撕裂尾修复:修复是写操作,不得与在写者并发
        // (占用即拒,不等待);本次 load 只用探测锁,写者锁在 backfill 创建时重新获取
        try (WriterLock probe = WriterLock.acquire(dir)) {
            repairTornTail(dir.resolve("log.jsonl"));
            return readLoaded(dir, headerFile, id);
        }
    }

    private Loaded readLoaded(Path dir, Path headerFile, Id<Session> id) throws IOException {
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

    /**
     * 撕裂尾修复:进程被杀于半行写入时,文件末尾留下无换行终止的残片。
     * 末行可解析(完整信封仅缺 '\n')则补换行;不可解析则截断到最后一条
     * 完整行(至多丢失半行)。仅在末行无换行终止时动作——内部行破损仍由
     * load 的解析/seq 校验 fail loud。
     *
     * @param log 会话日志路径(可不存在——尚无事件落盘的会话)
     */
    private static void repairTornTail(Path log) throws IOException {
        if (!Files.isRegularFile(log)) {
            return;
        }
        byte[] bytes = Files.readAllBytes(log);
        if (bytes.length == 0 || bytes[bytes.length - 1] == '\n') {
            return;
        }
        int lastNewline = -1;
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == '\n') {
                lastNewline = i;
                break;
            }
        }
        String fragment = new String(bytes, lastNewline + 1, bytes.length - lastNewline - 1,
            StandardCharsets.UTF_8);
        if (parsesAsObject(fragment)) {
            Files.writeString(log, "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            LOG.log(System.Logger.Level.WARNING,
                "torn tail in {0}: complete line missing newline, healed", log);
            return;
        }
        try (FileChannel channel = FileChannel.open(log, StandardOpenOption.WRITE)) {
            channel.truncate(lastNewline + 1);
            channel.force(true);
        }
        LOG.log(System.Logger.Level.WARNING,
            "torn tail in {0}: partial line of {1} bytes truncated",
            log, bytes.length - lastNewline - 1);
    }

    private static boolean parsesAsObject(String fragment) {
        try {
            JacksonBridge.read(fragment);
            return true;
        } catch (IllegalStateException notAnObject) {
            return false;
        }
    }

    private SessionWriter writer(Session session) throws IOException {
        SessionWriter existing = writers.get(session.id());
        if (existing != null) {
            return existing;
        }
        synchronized (writers) {
            existing = writers.get(session.id());
            if (existing != null) {
                return existing;
            }
            SessionWriter created = new SessionWriter(root.resolve(session.id().value()), codecs);
            writers.put(session.id(), created);
            return created;
        }
    }

    /**
     * 会话目录写者锁({@code .writer.lock}):tryLock 立即占用,不等待。
     * 第二写者(另一进程或同 JVM 另一实例的 {@code FileChannel})一律
     * {@link WriterLockException} fail loud——单写者保护 = 拒绝而非合并。
     */
    private static final class WriterLock implements AutoCloseable {
        private static final String LOCK_FILE = ".writer.lock";

        private final FileChannel channel;
        private final FileLock lock;

        private WriterLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        static WriterLock acquire(Path dir) throws IOException {
            Files.createDirectories(dir);
            Path lockFile = dir.resolve(LOCK_FILE);
            FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException overlapped) {
                closeQuietly(channel);
                throw new WriterLockException("session writer lock held by another writer"
                    + " in this JVM: " + lockFile, overlapped);
            }
            if (lock == null) {
                closeQuietly(channel);
                throw new WriterLockException("session writer lock held by another process: "
                    + lockFile);
            }
            return new WriterLock(channel, lock);
        }

        @Override
        public void close() {
            try {
                lock.release();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "writer lock release failed", e);
            } finally {
                closeQuietly(channel);
            }
        }

        private static void closeQuietly(FileChannel channel) {
            try {
                channel.close();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "writer lock channel close failed", e);
            }
        }
    }

    /** 单会话写入器:逐行追加;断点续写(文件行数即已写 seq+1;续写前先修复撕裂尾)。 */
    private static final class SessionWriter implements AutoCloseable {
        private final Path dir;
        private final SessionCodecRegistry codecs;
        private final WriterLock lock;
        private long writtenLines = -1;
        private boolean closed;

        SessionWriter(Path dir, SessionCodecRegistry codecs) throws IOException {
            this.dir = dir;
            this.codecs = codecs;
            this.lock = WriterLock.acquire(dir);
        }

        /** 释放写者锁;幂等(DISPOSED 与 scope close 双路径都可能到达)。 */
        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                lock.close();
            }
        }

        void backfill(Session session) throws IOException {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("header.json"),
                JacksonBridge.write(HeaderCodec.write(session.header())));
            rewrite(session);
        }

        synchronized void rewrite(Session session) throws IOException {
            Path log = dir.resolve("log.jsonl");
            repairTornTail(log);
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

        /**
         * 落盘 barrier:fsync 已有日志 + 对账(已写行数必须追平会话 seq)。
         * 对账是写失败的第一观察点——append 观察者异常按 session 契约 contained
         * (记日志不炸 append),写失败只在此显形;不符即抛,barrier 不放行。
         *
         * @param expectedSeq 调用方视角的事件数(= session.seq())
         */
        synchronized void flushBarrier(long expectedSeq) throws IOException {
            if (writtenLines != expectedSeq) {
                throw new IllegalStateException("durability barrier mismatch: "
                    + writtenLines + " lines written, session at seq " + expectedSeq);
            }
            Path log = dir.resolve("log.jsonl");
            if (!Files.isRegularFile(log)) {
                return;
            }
            try (FileChannel channel = FileChannel.open(log, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }

        private void writeEnvelope(LoggedEvent<?> entry) throws IOException {
            // 跳号拒绝:前一条写失败被 contained 吞过(洞里再写会把对账追平、把破损写实)
            if (entry.seq() > writtenLines) {
                throw new IllegalStateException("write gap: entry seq " + entry.seq()
                    + " but only " + writtenLines + " lines durable");
            }
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

    /** header 编解码(盘上字段 + 组合清单;parent/manifest 为 null 时省略)。 */
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
            if (header.manifest() != null) {
                java.util.List<JsonValue> rows = new java.util.ArrayList<>();
                for (CompositionManifest.Row row : header.manifest().rows()) {
                    JsonValue.Builder config = JsonValue.object();
                    row.config().forEach((key, value) ->
                        config.set(key, configValue(row.plugin(), key, value)));
                    rows.add(JsonValue.object().set("plugin", row.plugin())
                        .set("config", config.build()).build());
                }
                builder.set("manifest", new JsonValue.Arr(rows));
            }
            return builder.build();
        }

        static SessionHeader read(JsonValue.Obj obj) {
            CompositionManifest manifest = null;
            JsonValue manifestValue = obj.get("manifest");
            if (manifestValue instanceof JsonValue.Arr arr) {
                java.util.List<CompositionManifest.Row> rows = new java.util.ArrayList<>();
                for (JsonValue item : arr.items()) {
                    JsonValue.Obj row = (JsonValue.Obj) item;
                    java.util.Map<String, Object> config = new java.util.LinkedHashMap<>();
                    row.get("config").asObj().fields().forEach(
                        (key, value) -> config.put(key, plainValue(value)));
                    rows.add(new CompositionManifest.Row(row.get("plugin").asString(), config));
                }
                manifest = new CompositionManifest(rows);
            }
            return new SessionHeader((int) obj.get("version").asLong(),
                Session.newId(obj.get("id").asString()), obj.get("createdAt").asLong(),
                obj.get("parentSession").asString() == null ? null
                    : Session.newId(obj.get("parentSession").asString()),
                (int) obj.get("seedLength").asLong(), manifest);
        }

        /** manifest config 值只允许原语(YAML 边界),其余 fail loud。 */
        private static JsonValue configValue(String plugin, String key, Object value) {
            if (value instanceof String text) {
                return new JsonValue.Str(text);
            }
            if (value instanceof Number number) {
                return new JsonValue.Num(number.longValue());
            }
            if (value instanceof Boolean bool) {
                return new JsonValue.Bool(bool);
            }
            throw new IllegalStateException("manifest config '" + key + "' for '" + plugin
                + "' not primitive: " + value.getClass().getSimpleName());
        }

        private static Object plainValue(JsonValue value) {
            return switch (value) {
                case JsonValue.Str str -> str.value();
                case JsonValue.Num num -> num.value();
                case JsonValue.Bool bool -> bool.value();
                default -> throw new IllegalStateException("manifest config value not primitive: " + value);
            };
        }
    }
}
