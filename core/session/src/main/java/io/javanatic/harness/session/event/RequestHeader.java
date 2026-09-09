package io.javanatic.harness.session.event;

/**
 * 请求上下文事实(log-only,ignorable 遥测性):轮首落账的进程环境快照,
 * 提示词组装读它注入上下文 section——同日志必同提示词(R1)。
 *
 * @param cwd 进程工作目录(可空)
 * @param date ISO-8601 日期(clock 派生,重放确定)
 */
public record RequestHeader(long time, String cwd, String date) implements SessionEvent {

    @Override public String type() { return "request/header"; }

    @Override public boolean ignorable() { return true; }
}
