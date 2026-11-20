package io.javanatic.harness.workbench.rpc;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.Session;

import java.util.Optional;

/** SSE 日志传输接缝；本模块不提供生产 follower。 */
@FunctionalInterface
public interface SseEventSource {
    /**
     * 原子接续已验证快照与增量，返回前完成未知 session/日志损坏检查。
     * 失败自行清理已分配资源；成功后资源归 transport，不能返回 null。
     *
     * @param session 品牌化会话 id
     * @param afterSeq 排他游标，-1 包含 seq 0；超 head 由 transport 拒 400，不钳位
     * @return 独立连接；head 为打开时稳定的已验证日志头
     * @throws HttpFailureException 参数错误 400、未知 session 404、日志损坏 500
     */
    Opened open(Id<Session> session, long afterSeq);

    /** 已原子接续的连接；允许并发 close 与 next。 */
    interface Opened extends AutoCloseable {
        /** 打开时已验证的稳定日志头，空日志为 -1；不随增量变化。 */
        long head();

        /**
         * 严格按真实 seq 输出 afterSeq 排他范围，无重无丢；控制帧无 seq。
         * 可阻塞；空值为永久 EOF，不是暂时无事件。失败不得静默变成 EOF。
         *
         * @return 下一帧，或永久 EOF
         * @throws InterruptedException 连接撤销
         */
        Optional<SseFrame> next() throws InterruptedException;

        /** 幂等撤销，必须唤醒阻塞的 next；不得等待 socket writer。 */
        @Override
        void close();
    }

    /** 200 前可返回的具名 HTTP 失败；原因必须适合直接暴露给本地客户端。 */
    final class HttpFailureException extends RuntimeException {
        private final int status;

        public HttpFailureException(int status, String reason) {
            super(reason);
            if (status != 400 && status != 404 && status != 500) {
                throw new IllegalArgumentException("unsupported SSE failure status: " + status);
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("SSE failure reason must be named");
            }
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
