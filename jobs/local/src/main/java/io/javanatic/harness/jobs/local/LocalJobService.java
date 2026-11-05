package io.javanatic.harness.jobs.local;

import io.javanatic.harness.jobs.Job;
import io.javanatic.harness.jobs.JobException;
import io.javanatic.harness.jobs.JobFailureReason;
import io.javanatic.harness.jobs.JobNotFoundException;
import io.javanatic.harness.jobs.JobRequest;
import io.javanatic.harness.jobs.JobService;
import io.javanatic.harness.jobs.JobSlice;
import io.javanatic.harness.jobs.JobSnapshot;
import io.javanatic.harness.jobs.JobState;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.LoggedEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本地后台任务 Provider（it30 停 2）。进程纪律照 it29：TERM（含后代）→ 排水宽限 → KILL →
 * 复验等待。事实源 = 事件账（B 条裁定）：每次状态变化先 session.append 后更新内存（先持久
 * 后可见）；Lost 由装配期 fold 事件账产生（懒标记——会话未被再次装配则磁盘/账面原状）。
 * 输出捕获落文件（jobsRoot/&lt;sessionId&gt;/&lt;jobId&gt;/output.part），达上限停止捕获改排水
 * （不杀任务），Lost/前生命期终态任务作为遗物只读。state.json 不存在——事件账是唯一状态源。
 */
public final class LocalJobService implements JobService {

    /** TERM 后排水宽限与 KILL 后复验等待（it29 矩阵口径）。 */
    static final Duration DRAIN_GRACE = Duration.ofSeconds(2);
    static final Duration KILL_RECHECK = Duration.ofSeconds(5);

    private final Path jobsRoot;
    private final Map<Id<Job>, Entry> jobs = new ConcurrentHashMap<>();
    private final AtomicReference<Session> session = new AtomicReference<>();

    /** @param jobsRoot 任务树根（其下按会话 id 分目录）；测试可注入临时目录 */
    public LocalJobService(Path jobsRoot) {
        this.jobsRoot = Objects.requireNonNull(jobsRoot, "jobsRoot");
    }

    /**
     * 绑定所属会话并执行一次 Lost 扫描（懒标记：这是重启后对前生命期任务的首次观测）。
     * fold 事件账按 id 末值胜；末值 Running 且不在本生命期活表 → 追加 Lost 事件并登记遗物。
     * @param session 任务所属会话（事件账与所有权的锚点）
     */
    public synchronized void bindSession(Session session) {
        Objects.requireNonNull(session, "session");
        if (this.session.get() == session) {
            return;
        }
        this.session.set(session);
        Map<Id<Job>, JobSnapshot> folded = new HashMap<>();
        for (LoggedEvent<?> logged : session.events()) {
            if (logged.event() instanceof JobSnapshotEvent event) {
                folded.put(event.snapshot().id(), event.snapshot());
            }
        }
        Instant now = Instant.now();
        for (Map.Entry<Id<Job>, JobSnapshot> fold : folded.entrySet()) {
            JobSnapshot snapshot = fold.getValue();
            if (jobs.containsKey(fold.getKey())) {
                continue;
            }
            JobSnapshot restored = snapshot;
            if (snapshot.state() instanceof JobState.Running running) {
                restored = new JobSnapshot(snapshot.id(), snapshot.commandSummary(),
                        new JobState.Lost(running.startedAt(), now), snapshot.capturedBytes(),
                        snapshot.outputTruncated());
                session.append(new JobSnapshotEvent(System.currentTimeMillis(), restored));
            }
            Path output = sessionDir(session).resolve(fold.getKey().value()).resolve("output.part");
            jobs.put(fold.getKey(), new Entry(restored, null, output, runningStartOf(snapshot)));
        }
    }

    @Override
    public JobSnapshot start(JobRequest request) {
        Session session = requireSession();
        Id<Job> id = Job.newId("job-" + System.currentTimeMillis() + "-" + randomSuffix());
        Instant startedAt = Instant.now();
        Path output;
        try {
            output = sessionDir(session).resolve(id.value()).resolve("output.part");
            Files.createDirectories(output.getParent());
            Files.createFile(output);
        } catch (IOException e) {
            throw new JobException("cannot create job output file for " + id.value(), e);
        }
        Process process;
        try {
            process = new ProcessBuilder(shellPrefix(request.command()))
                    .directory(request.workingDirectory().toFile())
                    .start();
        } catch (IOException e) {
            JobSnapshot failed = new JobSnapshot(id, summarize(request),
                    new JobState.Failed(JobFailureReason.SPAWN_FAILED, -1,
                            startedAt, Instant.now()), 0, false);
            session.append(new JobSnapshotEvent(System.currentTimeMillis(), failed));
            jobs.put(id, new Entry(failed, null, output, startedAt.toEpochMilli()));
            throw new JobException("spawn failed: " + summarize(request), e);
        }
        Entry entry = new Entry(new JobSnapshot(id, summarize(request),
                new JobState.Running(startedAt), 0, false), process, output,
                startedAt.toEpochMilli(), request.outputCapBytes());
        session.append(new JobSnapshotEvent(System.currentTimeMillis(), entry.snapshot.get()));
        jobs.put(id, entry);
        startCapture(entry, request, process);
        startWatchdog(entry, request, process);
        return entry.snapshot.get();
    }

    @Override
    public JobSnapshot snapshot(Id<Job> id) {
        return entry(id).snapshot.get();
    }

    @Override
    public JobSlice read(Id<Job> id, long fromByte, int maxBytes) {
        Entry entry = entry(id);
        long captured;
        try {
            captured = Files.size(entry.output);
        } catch (IOException e) {
            throw new JobException("cannot stat job output for " + id.value(), e);
        }
        if (fromByte < 0 || fromByte > captured) {
            throw new IllegalArgumentException("fromByte out of bounds: " + fromByte + " / " + captured);
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        long end = Math.min(fromByte + maxBytes, captured);
        byte[] buffer = new byte[(int) (end - fromByte)];
        try (InputStream in = Files.newInputStream(entry.output)) {
            long skipped = in.skip(fromByte);
            if (skipped != fromByte || in.read(buffer) != buffer.length) {
                throw new JobException("short read on job output for " + id.value());
            }
        } catch (IOException e) {
            throw new JobException("cannot read job output for " + id.value(), e);
        }
        int textEnd = buffer.length;
        if (end < captured) {
            textEnd = trimPartialUtf8(buffer);
        }
        String text = new String(buffer, 0, textEnd, StandardCharsets.UTF_8);
        return new JobSlice(text, fromByte + textEnd, captured, entry.snapshot.get().outputTruncated());
    }

    @Override
    public boolean cancel(Id<Job> id) {
        Entry entry = entry(id);
        if (entry.snapshot.get().state().terminal()) {
            return false;
        }
        killTree(entry.process);
        if (entry.finalized.compareAndSet(false, true)) {
            finalizeSnapshot(entry, new JobState.Cancelled(
                    startedOf(entry.snapshot.get()), Instant.now()));
        }
        return true;
    }

    @Override
    public List<JobSnapshot> list() {
        List<Entry> ordered = new ArrayList<>(jobs.values());
        ordered.sort(Comparator.comparingLong(entry -> entry.startedAtMillis));
        return ordered.stream().map(entry -> entry.snapshot.get()).toList();
    }

    /** 拥有 scope 关闭：逐任务收敛为 Cancelled（事件入账）；输出文件保留为遗物。 */
    @Override
    public void close() {
        for (Entry entry : jobs.values()) {
            if (!entry.snapshot.get().state().terminal()) {
                cancel(entry.snapshot.get().id());
            }
        }
    }

    private Session requireSession() {
        Session session = this.session.get();
        if (session == null) {
            throw new IllegalStateException("job service session not attached (agent/created not observed)");
        }
        return session;
    }

    private Path sessionDir(Session session) {
        return jobsRoot.resolve(session.id().value());
    }

    private void startCapture(Entry entry, JobRequest request, Process process) {
        AtomicLong captured = entry.captured;
        for (InputStream stream : List.of(process.getInputStream(), process.getErrorStream())) {
            Thread.startVirtualThread(() -> {
                byte[] buffer = new byte[8192];
                try (stream; OutputStream out = Files.newOutputStream(entry.output,
                        StandardOpenOption.APPEND)) {
                    int read;
                    while ((read = stream.read(buffer)) >= 0) {
                        if (entry.outputTruncatedByCap.get()) {
                            continue;
                        }
                        if (captured.get() >= request.outputCapBytes()) {
                            entry.outputTruncatedByCap.set(true);
                            continue;
                        }
                        int room = (int) Math.min(read, request.outputCapBytes() - captured.get());
                        synchronized (out) {
                            out.write(buffer, 0, room);
                        }
                        captured.addAndGet(room);
                    }
                } catch (IOException e) {
                    // 捕获失败不终止任务；进程面由看门狗与终局快照负责
                }
            });
        }
    }

    private void startWatchdog(Entry entry, JobRequest request, Process process) {
        Thread.startVirtualThread(() -> {
            boolean exited;
            try {
                exited = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            JobState terminal;
            if (exited) {
                terminal = process.exitValue() == 0
                        ? new JobState.Succeeded(0, startedOf(entry.snapshot.get()), Instant.now())
                        : new JobState.Failed(JobFailureReason.EXIT_NONZERO,
                                process.exitValue(), startedOf(entry.snapshot.get()), Instant.now());
            } else {
                killTree(process);
                terminal = new JobState.Failed(JobFailureReason.TIMED_OUT, exitOf(process),
                        startedOf(entry.snapshot.get()), Instant.now());
            }
            if (entry.finalized.compareAndSet(false, true)) {
                finalizeSnapshot(entry, terminal);
            }
        });
    }

    /** 终局三步：等捕获线程排干 → 事件入账（先持久）→ 内存可见（后可见）。 */
    private void finalizeSnapshot(Entry entry, JobState terminal) {
        try {
            if (entry.process != null) {
                entry.process.waitFor(KILL_RECHECK.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        JobSnapshot snapshot = new JobSnapshot(entry.snapshot.get().id(),
                entry.snapshot.get().commandSummary(), terminal, entry.captured.get(),
                entry.outputTruncatedByCap.get());
        Session session = requireSession();
        session.append(new JobSnapshotEvent(System.currentTimeMillis(), snapshot));
        entry.snapshot.set(snapshot);
    }

    private static long runningStartOf(JobSnapshot snapshot) {
        return startedOf(snapshot).toEpochMilli();
    }

    private static Instant startedOf(JobSnapshot snapshot) {
        JobState state = snapshot.state();
        if (state instanceof JobState.Running running) {
            return running.startedAt();
        }
        if (state instanceof JobState.Succeeded succeeded) {
            return succeeded.startedAt();
        }
        if (state instanceof JobState.Failed failed) {
            return failed.startedAt();
        }
        if (state instanceof JobState.Cancelled cancelled) {
            return cancelled.startedAt();
        }
        return ((JobState.Lost) state).startedAt();
    }

    private static int exitOf(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    /** TERM 含后代 → 排水宽限 → KILL → 复验等待（it29 矩阵）。 */
    private static void killTree(Process process) {
        if (process == null) {
            return;
        }
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 输出文件尾若为不完整 UTF-8 序列且后续还有字节，退到字符边界。 */
    private static int trimPartialUtf8(byte[] buffer) {
        int end = buffer.length;
        for (int back = 1; back <= 3 && end - back >= 0; back++) {
            byte b = buffer[end - back];
            if ((b & 0x80) == 0) {
                return end;
            }
            if ((b & 0xC0) == 0xC0) {
                int expected = Integer.numberOfLeadingZeros(b << 24) - 24 + 1;
                return expected == back ? end : end - back;
            }
        }
        return end;
    }

    private static String[] shellPrefix(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win")
                ? new String[] {"pwsh", "-NoProfile", "-Command", command}
                : new String[] {"/bin/sh", "-c", command};
    }

    private static String summarize(JobRequest request) {
        String command = request.command();
        return command.length() <= 200 ? command : command.substring(0, 200) + "…";
    }

    private static String randomSuffix() {
        return String.format("%08x", new SecureRandom().nextInt());
    }

    private Entry entry(Id<Job> id) {
        Entry entry = jobs.get(Objects.requireNonNull(id, "id"));
        if (entry == null) {
            throw new JobNotFoundException(id.value());
        }
        return entry;
    }

    /** 运行时记录：快照原子可见；finalized 保证终局事件恰一次。 */
    private static final class Entry {
        final AtomicReference<JobSnapshot> snapshot;
        final Process process;
        final Path output;
        final long startedAtMillis;
        final long capBytes;
        final AtomicLong captured = new AtomicLong();
        final AtomicBoolean finalized = new AtomicBoolean();
        final AtomicBoolean outputTruncatedByCap = new AtomicBoolean();

        Entry(JobSnapshot initial, Process process, Path output, long startedAtMillis) {
            this(initial, process, output, startedAtMillis, Long.MAX_VALUE);
        }

        Entry(JobSnapshot initial, Process process, Path output, long startedAtMillis, long capBytes) {
            this.snapshot = new AtomicReference<>(initial);
            this.process = process;
            this.output = output;
            this.startedAtMillis = startedAtMillis;
            this.capBytes = capBytes;
        }
    }
}
