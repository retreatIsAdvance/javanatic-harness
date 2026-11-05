package io.javanatic.harness.jobs.tool;

import io.javanatic.harness.jobs.JobSlice;
import io.javanatic.harness.jobs.JobSnapshot;
import io.javanatic.harness.jobs.JobState;
import java.util.Locale;

/**
 * 任务面到结果文本的逐字映射（停 3 评审重点①措辞纪律）：读结果三态逐字区分——
 * 「暂无更多已捕获输出（任务仍在运行）」绝不写成「任务没有输出」（运行中的空窗
 * 不是无产出的事实承诺）；截断恒称「捕获已达上限」，不称「输出结束」。模板由
 * 测试钉死，禁词突变必红（it29 同款纪律）。
 */
final class JobToolOutput {

    private JobToolOutput() {
    }

    /** 快照单行：状态词 + 量纲事实，不解读因果。 */
    static String status(JobSnapshot snapshot) {
        return "job " + snapshot.id().value() + " " + stateWord(snapshot.state())
                + " captured=" + snapshot.capturedBytes() + "B"
                + (snapshot.outputTruncated() ? "（捕获已达上限）" : "");
    }

    /** 读结果：首行量纲 + 输出本体 + 尾行三态逐字标记。 */
    static String read(JobSnapshot snapshot, JobSlice slice) {
        StringBuilder text = new StringBuilder();
        text.append("[job ").append(snapshot.id().value()).append("] read ")
                .append(slice.nextFromByte() - slice.text().getBytes().length).append('-')
                .append(slice.nextFromByte()).append(" of ").append(slice.capturedBytes())
                .append("B").append('\n');
        text.append(slice.text());
        text.append('\n').append(readMarker(slice, snapshot));
        return text.toString();
    }

    /** 三态标记：读尽（终态）/ 暂无更多（运行中）/ 还有更多；截断独立追加。 */
    static String readMarker(JobSlice slice, JobSnapshot snapshot) {
        StringBuilder marker = new StringBuilder("[");
        if (slice.endOfCapture()) {
            marker.append(snapshot.state().terminal()
                    ? "已读尽已捕获输出（任务已终局）"
                    : "暂无更多已捕获输出（任务仍在运行，稍后可从游标 "
                            + slice.nextFromByte() + " 再读）");
        } else {
            marker.append("还有更多已捕获输出（下一游标 ").append(slice.nextFromByte()).append("）");
        }
        marker.append(']');
        if (slice.outputTruncated()) {
            marker.append("[捕获已达上限，不会再有新输出（输出被截断）]");
        }
        return marker.toString();
    }

    /** 状态词：五态判别联合的稳定文案（Lost 只说失联，不说失败）。 */
    static String stateWord(JobState state) {
        return switch (state) {
            case JobState.Running running -> "running";
            case JobState.Succeeded succeeded ->
                "succeeded(exit=" + succeeded.exitCode() + ")";
            case JobState.Failed failed -> "failed(" + failed.reason().name().toLowerCase(Locale.ROOT)
                    + ",exit=" + failed.exitCode() + ")";
            case JobState.Cancelled cancelled -> "cancelled";
            case JobState.Lost lost -> "lost(失联——前宿主生命期任务，不冒充可续接)";
        };
    }

    /** 工具层错误文本（稳定前缀 + 事实，不猜测原因）。 */
    static String error(String message) {
        return "任务错误：" + message;
    }
}
