package io.javanatic.harness.jobs.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.javanatic.harness.jobs.Job;
import io.javanatic.harness.jobs.JobFailureReason;
import io.javanatic.harness.jobs.JobSlice;
import io.javanatic.harness.jobs.JobSnapshot;
import io.javanatic.harness.jobs.JobState;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 读三态 + 五状态词的逐字模板钉死（停 3 评审重点①）；禁词面由突变测试另证。 */
class JobToolOutputTest {

    private static final Instant T0 = Instant.parse("2026-11-05T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-05T00:01:00Z");

    @Test
    void runningEndOfCaptureSaysTemporarilyNotNoOutput() {
        JobSnapshot running = snapshot(new JobState.Running(T0), 0, false);
        String marker = JobToolOutput.readMarker(new JobSlice("", 0, 0, false), running);
        assertThat(marker).contains("暂无更多已捕获输出（任务仍在运行");
        assertThat(marker).doesNotContain("任务没有输出").doesNotContain("无输出");
    }

    @Test
    void terminalEndOfCaptureSaysFullyRead() {
        JobSnapshot done = snapshot(new JobState.Succeeded(0, T0, T1), 8, false);
        String marker = JobToolOutput.readMarker(new JobSlice("abc", 3, 3, false), done);
        assertThat(marker).contains("已读尽已捕获输出（任务已终局）");
    }

    @Test
    void moreAvailableCarriesNextCursor() {
        JobSnapshot running = snapshot(new JobState.Running(T0), 100, false);
        String marker = JobToolOutput.readMarker(new JobSlice("abc", 3, 100, false), running);
        assertThat(marker).contains("还有更多已捕获输出（下一游标 3）");
    }

    @Test
    void truncationMarkerNeverSaysOutputEnded() {
        JobSnapshot truncated = snapshot(new JobState.Running(T0), 1024, true);
        String marker = JobToolOutput.readMarker(new JobSlice("", 1024, 1024, true), truncated);
        assertThat(marker).contains("捕获已达上限，不会再有新输出（输出被截断）");
        assertThat(marker).doesNotContain("输出结束").doesNotContain("读尽");
    }

    @Test
    void stateWordsAreStable() {
        assertThat(JobToolOutput.stateWord(new JobState.Running(T0))).isEqualTo("running");
        assertThat(JobToolOutput.stateWord(new JobState.Succeeded(0, T0, T1)))
                .isEqualTo("succeeded(exit=0)");
        assertThat(JobToolOutput.stateWord(
                new JobState.Failed(JobFailureReason.TIMED_OUT, 137, T0, T1)))
                .isEqualTo("failed(timed_out,exit=137)");
        assertThat(JobToolOutput.stateWord(new JobState.Cancelled(T0, T1))).isEqualTo("cancelled");
        assertThat(JobToolOutput.stateWord(new JobState.Lost(T0, T1)))
                .contains("lost").contains("失联").contains("不冒充可续接");
    }

    @Test
    void statusLineCarriesQuantities() {
        String line = JobToolOutput.status(snapshot(new JobState.Running(T0), 4096, true));
        assertThat(line).startsWith("job ").contains(" running captured=4096B").contains("捕获已达上限");
    }

    private static JobSnapshot snapshot(JobState state, long captured, boolean truncated) {
        return new JobSnapshot(Job.newId("job-tpl"), "mvn -q package", state, captured, truncated);
    }
}
