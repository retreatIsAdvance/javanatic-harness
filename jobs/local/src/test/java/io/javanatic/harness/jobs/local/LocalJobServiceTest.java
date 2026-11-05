package io.javanatic.harness.jobs.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.javanatic.harness.jobs.Job;
import io.javanatic.harness.jobs.JobFailureReason;
import io.javanatic.harness.jobs.JobNotFoundException;
import io.javanatic.harness.jobs.JobRequest;
import io.javanatic.harness.jobs.JobService;
import io.javanatic.harness.jobs.JobSlice;
import io.javanatic.harness.jobs.JobSnapshot;
import io.javanatic.harness.jobs.JobState;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionHeader;
import io.javanatic.harness.session.event.LoggedEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Provider 行为面：成功/非零退出/取消/超时/限额不杀/事件账 fold 标 Lost/游标与 fail-loud。 */
class LocalJobServiceTest {

    @TempDir
    Path root;

    @Test
    void startBeforeSessionBoundFailsLoud() {
        LocalJobService service = new LocalJobService(root);
        assertThatThrownBy(() -> service.start(request("echo hi", Duration.ofSeconds(10), 4096)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session not attached");
    }

    @Test
    void succeedsAndReadsOutput() throws Exception {
        JobService service = bound();
        JobSnapshot started = service.start(request("echo hello-jobs", Duration.ofSeconds(15), 65536));
        JobSnapshot terminal = awaitTerminal(service, started.id());
        assertThat(terminal.state()).isInstanceOf(JobState.Succeeded.class);
        JobSlice slice = service.read(started.id(), 0, 65536);
        assertThat(slice.text()).contains("hello-jobs");
        assertThat(slice.endOfCapture()).isTrue();
        assertThat(slice.outputTruncated()).isFalse();
        service.close();
    }

    @Test
    void nonZeroExitFailsWithReason() throws Exception {
        JobService service = bound();
        JobSnapshot started = service.start(request("exit 3", Duration.ofSeconds(15), 4096));
        JobSnapshot terminal = awaitTerminal(service, started.id());
        assertThat(terminal.state()).isInstanceOf(JobState.Failed.class);
        assertThat(((JobState.Failed) terminal.state()).reason()).isEqualTo(JobFailureReason.EXIT_NONZERO);
        assertThat(((JobState.Failed) terminal.state()).exitCode()).isEqualTo(3);
        service.close();
    }

    @Test
    void cancelConvergesRunningJob() throws Exception {
        JobService service = bound();
        JobSnapshot started = service.start(request(longSleep(), Duration.ofSeconds(60), 4096));
        assertThat(service.cancel(started.id())).isTrue();
        assertThat(awaitTerminal(service, started.id()).state()).isInstanceOf(JobState.Cancelled.class);
        assertThat(service.cancel(started.id())).isFalse();
        service.close();
    }

    @Test
    void timeoutFailsTimedOut() throws Exception {
        JobService service = bound();
        JobSnapshot started = service.start(request(longSleep(), Duration.ofMillis(800), 4096));
        JobSnapshot terminal = awaitTerminal(service, started.id());
        assertThat(terminal.state()).isInstanceOf(JobState.Failed.class);
        assertThat(((JobState.Failed) terminal.state()).reason()).isEqualTo(JobFailureReason.TIMED_OUT);
        service.close();
    }

    @Test
    void capStopsCaptureWithoutKilling() throws Exception {
        JobService service = bound();
        JobSnapshot started = service.start(request(floodThenSleep(), Duration.ofSeconds(30), 1024));
        JobSnapshot terminal = awaitTerminal(service, started.id());
        assertThat(terminal.state())
                .as("输出超限停止捕获但任务照常完成（停 1 裁决②）")
                .isInstanceOf(JobState.Succeeded.class);
        assertThat(terminal.outputTruncated()).isTrue();
        assertThat(terminal.capturedBytes()).isLessThanOrEqualTo(1024);
        service.close();
    }

    @Test
    void ledgerFoldMarksPriorLifeRunningAsLost() throws Exception {
        Session session = newSession();
        LocalJobService first = new LocalJobService(root);
        first.bindSession(session);
        first.start(request(longSleep(), Duration.ofSeconds(60), 4096));
        awaitEventCount(session, 1);
        // 模拟宿主 A 失联后宿主 B 装配同一会话：fold 事件账 → Running 标 Lost（懒标记）
        LocalJobService second = new LocalJobService(root);
        second.bindSession(session);
        List<JobSnapshot> list = second.list();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).state()).isInstanceOf(JobState.Lost.class);
        awaitEventCount(session, 2);
        assertThat(second.list().get(0).state().terminal()).isTrue();
        first.close();
        second.close();
    }

    @Test
    void unknownIdAndBadCursorFailLoud() throws Exception {
        JobService service = bound();
        Id<Job> unknown = Job.newId("job-none");
        assertThatThrownBy(() -> service.snapshot(unknown)).isInstanceOf(JobNotFoundException.class);
        JobSnapshot started = service.start(request("echo x", Duration.ofSeconds(10), 4096));
        awaitTerminal(service, started.id());
        assertThatThrownBy(() -> service.read(started.id(), 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.read(started.id(), 100000, 10))
                .isInstanceOf(IllegalArgumentException.class);
        service.close();
    }

    @Test
    void terminalClassificationPureFunction() {
        Instant t0 = Instant.parse("2026-11-05T00:00:00Z");
        assertThat(LocalJobService.terminalFor(true, 143, true, t0))
                .isInstanceOf(JobState.Cancelled.class);
        assertThat(LocalJobService.terminalFor(true, 0, false, t0))
                .isInstanceOf(JobState.Succeeded.class);
        assertThat(LocalJobService.terminalFor(true, 143, false, t0))
                .isInstanceOf(JobState.Failed.class)
                .extracting(st -> ((JobState.Failed) st).reason())
                .isEqualTo(JobFailureReason.EXIT_NONZERO);
        assertThat(LocalJobService.terminalFor(true, 1, false, t0))
                .isInstanceOf(JobState.Failed.class);
        assertThat(LocalJobService.terminalFor(false, -1, false, t0))
                .isInstanceOf(JobState.Failed.class)
                .extracting(st -> ((JobState.Failed) st).reason())
                .isEqualTo(JobFailureReason.TIMED_OUT);
    }

    @Test
    void cancelRaceShakeNeverMisclassifies() throws Exception {
        // 复跑竞态回归钉：瞬时死于 TERM 的进程 × 50 次取消循环，恒 Cancelled
        JobService service = bound();
        for (int i = 0; i < 50; i++) {
            JobSnapshot started = service.start(request(longSleep(), Duration.ofSeconds(60), 1024));
            assertThat(service.cancel(started.id())).isTrue();
            JobSnapshot terminal = awaitTerminal(service, started.id());
            assertThat(terminal.state())
                    .as("iteration " + i)
                    .isInstanceOf(JobState.Cancelled.class);
        }
        service.close();
    }

    @Test
    void externalKillStillFailsExitNonZero() throws Exception {
        // 反向负例：非取消路径的 TERM 死亡（进程自杀 143）必须仍落 EXIT_NONZERO
        JobService service = bound();
        String selfTerm = windows() ? "exit 5" : "kill -TERM $$";
        JobSnapshot started = service.start(request(selfTerm, Duration.ofSeconds(15), 4096));
        JobSnapshot terminal = awaitTerminal(service, started.id());
        assertThat(terminal.state()).isInstanceOf(JobState.Failed.class);
        assertThat(((JobState.Failed) terminal.state()).reason()).isEqualTo(JobFailureReason.EXIT_NONZERO);
        service.close();
    }

    private LocalJobService bound() throws IOException {
        LocalJobService service = new LocalJobService(root);
        service.bindSession(newSession());
        return service;
    }

    private Session newSession() {
        Id<Session> id = Session.newId("itest-" + System.nanoTime());
        return Session.create(id, null,
                new SessionHeader(SessionHeader.FORMAT_VERSION, id, System.currentTimeMillis(), null, 0, null));
    }

    private static JobRequest request(String command, Duration timeout, long cap) {
        return new JobRequest(command, Path.of(System.getProperty("user.dir")), timeout, cap);
    }

    /** POSIX / pwsh 双平台的平台分支命令。 */
    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String longSleep() {
        return windows() ? "Start-Sleep -Seconds 60" : "sleep 60";
    }

    private static String floodThenSleep() {
        return windows()
                ? "1..3000 | ForEach-Object { \"0123456789\" }; Start-Sleep -Seconds 1"
                : "i=1; while [ $i -le 3000 ]; do echo 0123456789; i=$((i+1)); done; sleep 1";
    }

    private static JobSnapshot awaitTerminal(JobService service, Id<Job> id)
            throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            JobSnapshot snapshot = service.snapshot(id);
            if (snapshot.state().terminal()) {
                return snapshot;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("job did not reach terminal state: " + service.snapshot(id));
    }

    private static void awaitEventCount(Session session, int expected) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            if (session.events().stream().filter(LocalJobServiceTest::isJobEvent).count() >= expected) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("event count not reached");
    }

    private static boolean isJobEvent(LoggedEvent<?> logged) {
        return logged.event() instanceof JobSnapshotEvent;
    }
}
