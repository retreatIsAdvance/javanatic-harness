package io.javanatic.harness.examples.headless;

import io.javanatic.harness.interaction.approval.ApprovalPrompt;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.tools.ApprovalService.ApprovalRequest;
import io.javanatic.harness.workbench.rpc.ApprovalRelay;
import io.javanatic.harness.workbench.rpc.RpcException;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * serve 写面的审批宿主（it38 H2）：一个共享 {@link ApprovalRelay}，按 REQUEST
 * 信号身份挂载每轮租约（{@link RelayApprovalPrompt}），并按**根会话**归属汇总
 * 待决清单供 SSE 控制帧与 {@code approval/respond} 使用。
 *
 * <p>归属规则：{@link #bind} 的 owner 是根会话（通常是发起轮的那个 session）；
 * delegation 子代轮以自身 session 建租约、却进父 owner 的 feed——子代审批出现在
 * 驱动它的浏览器标签页里，这正是「会话树」的唯一可观察面。租约元数据保留子代
 * 会话 id，feed 路由不混用。
 *
 * <p>回收：租约由宿主在每轮 finally 按信号定向 {@link #closeSignal}（H2 按轮挂载；
 * 同一 owner 的父子代各自关自己那代），owner 级 {@link #closeTurn(String)} 仅用于
 * 整页退场；{@code close} 撤销全部租约并关闭共享 relay，幂等。revision 是 feed 的单调
 * 内容修订（只随 pending 集合变化递增），不是 R1 seq，也不是 SSE id；不保留历史，
 * 客户端按完整替换语义处理。
 */
final class WebApprovalHub {

    /** 一个浏览器标签页的待决视图：完整替换，revision 单调。 */
    record Snapshot(long revision, List<RelayApprovalPrompt.Pending> pending) {
        Snapshot {
            pending = List.copyOf(pending);
        }
    }

    private record Lease(String owner, RelayApprovalPrompt prompt) { }

    /** feed 修订状态：内容（requestId 序列）变化才递增。 */
    private static final class Feed {
        private long revision;
        private List<String> lastIds = List.of();
    }

    private final Object lock = new Object();
    private final ApprovalRelay relay = new ApprovalRelay();
    private final long approvalSeconds;
    private final IdentityHashMap<AbortSignal, Lease> bindings = new IdentityHashMap<>();
    private final Map<String, Feed> feeds = new LinkedHashMap<>();
    private final ApprovalPrompt prompt = this::ask;

    /**
     * @param approvalSeconds 审批空闲上限秒数（有效值由装配面单源计算；0 = 不设限）
     */
    WebApprovalHub(long approvalSeconds) {
        this.approvalSeconds = approvalSeconds;
    }

    /** 写面提示实现：未知信号 fail-closed（false），不登记任何租约。 */
    ApprovalPrompt prompt() {
        return prompt;
    }

    private boolean ask(ApprovalRequest request, AbortSignal signal) {
        Lease lease;
        synchronized (lock) {
            lease = bindings.get(signal);
        }
        return lease != null && lease.prompt().ask(request, signal);
    }

    /**
     * 把一轮的真实 REQUEST 信号挂到 owner 的 feed；同信号重绑幂等（重试/多 step
     * 共用一个 signal，只应有一个租约）。
     *
     * @return true = 本次首次绑定（宿主据此只挂一次轮毕回收）；false = 重绑
     */
    boolean bind(AbortSignal signal, String owner, Id<Session> session, int turn) {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(session, "session");
        synchronized (lock) {
            if (bindings.containsKey(signal)) {
                return false;
            }
            bindings.put(signal,
                new Lease(owner, new RelayApprovalPrompt(relay, session, turn, approvalSeconds, true)));
            feeds.computeIfAbsent(owner, ignored -> new Feed());
            return true;
        }
    }

    /** owner 的完整待决视图；内容变化时 revision 递增。 */
    Snapshot snapshot(String owner) {
        synchronized (lock) {
            List<RelayApprovalPrompt.Pending> merged = new ArrayList<>();
            bindings.values().stream()
                .filter(lease -> lease.owner().equals(owner))
                .forEach(lease -> merged.addAll(lease.prompt().snapshot().pending()));
            Feed feed = feeds.get(owner);
            if (feed == null) {
                // 只为真实租约持有者建 feed：任意 session 查询不得无界增长宿主状态
                return new Snapshot(0, merged);
            }
            List<String> ids = merged.stream().map(RelayApprovalPrompt.Pending::requestId).toList();
            if (!ids.equals(feed.lastIds)) {
                feed.revision++;
                feed.lastIds = ids;
            }
            return new Snapshot(feed.revision, merged);
        }
    }

    /** 一次性结算；错误码由共享 relay 决定（-32007/-32008/-32009）。 */
    void respond(String requestId, boolean allow) throws RpcException {
        relay.settle(requestId, allow ? ApprovalRelay.Decision.ALLOW : ApprovalRelay.Decision.REJECT);
    }

    /** 关闭 owner 名下全部租约（H2：该轮结束即关；后台例外由宿主另管 runtime）。 */
    void closeTurn(String owner) {
        List<RelayApprovalPrompt> closing = new ArrayList<>();
        synchronized (lock) {
            var iterator = bindings.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<AbortSignal, Lease> entry = iterator.next();
                if (entry.getValue().owner().equals(owner)) {
                    closing.add(entry.getValue().prompt());
                    iterator.remove();
                }
            }
        }
        closeAll(closing);
    }

    /**
     * 按信号身份关闭单个租约（H2 按轮挂载的定向回收）：同一 owner 下父子代可能
     * 各有在途轮，轮毕只关自己那一代——{@link #closeTurn(String)} 的 owner 级
     * 撤销会误伤仍然活着的子代。未绑定/重复撤销是幂等空操作。
     */
    void closeSignal(AbortSignal signal) {
        Lease lease;
        synchronized (lock) {
            lease = bindings.remove(signal);
        }
        if (lease != null) {
            closeAll(List.of(lease.prompt()));
        }
    }

    /** 活跃租约数（治理/测试观测面）。 */
    int leaseCount() {
        synchronized (lock) {
            return bindings.size();
        }
    }

    /** 已建 feed 数（测试观测面）：只随真实租约绑定增长，不随查询增长。 */
    int feedCount() {
        synchronized (lock) {
            return feeds.size();
        }
    }

    /** owner 名下活跃租约数。 */
    int leaseCount(String owner) {
        synchronized (lock) {
            return (int) bindings.values().stream()
                .filter(lease -> lease.owner().equals(owner))
                .count();
        }
    }

    /** 撤销全部租约并关闭共享 relay；幂等。 */
    void close() {
        List<RelayApprovalPrompt> closing = new ArrayList<>();
        synchronized (lock) {
            closing.addAll(bindings.values().stream().map(Lease::prompt).toList());
            bindings.clear();
        }
        closeAll(closing);
        relay.close();
    }

    private static void closeAll(List<RelayApprovalPrompt> prompts) {
        RuntimeException failure = null;
        for (RelayApprovalPrompt closing : prompts) {
            try {
                closing.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
