package io.javanatic.harness.plan;

import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;

import java.util.List;
import java.util.Objects;

/**
 * 计划模式的 logged 状态服务（KEY "plan-mode"）。生效态 = 日志纯 fold
 * （plan/mode 末值胜，无事件 inactive）——resume/fork 不需内存镜像即恢复，
 * 无竞态面。提示词经 {@link #sectionFor} 按 fold 渲染。
 *
 * <p>模式翻转由 exit_plan_mode 工具直接落账（dsh 的步界 pending 提交依赖
 * 步级 pre-step 钩子，JH 的 PRE_STEP 是轮级裁决——直接落账使「下一步生效」
 * 字面为真：工具批 join 后的 assemble 读到新 fold）。未来的 /plan 命令若需
 * 轮中排队提交，再按 dsh 形状引入步界扩展点（挂账，commands seam）。
 */
public final class PlanModeService {

    /** 本服务的服务键。 */
    public static final ServiceKey<PlanModeService> KEY = new ServiceKey<>("plan-mode");

    private final String section;

    /** @param section 部署方拥有的规划期指导文（非空白） */
    public PlanModeService(String section) {
        Objects.requireNonNull(section, "section");
        if (section.isBlank()) {
            throw new IllegalArgumentException("plan section must be non-blank");
        }
        this.section = section;
    }

    /**
     * fold 日志前缀的计划模式态（末值胜；无 plan/mode 即 inactive）。
     *
     * @param events 会话日志（或其任意前缀）
     */
    public static boolean foldActive(List<LoggedEvent<? extends SessionEvent>> events) {
        boolean active = false;
        for (LoggedEvent<? extends SessionEvent> entry : events) {
            if (entry.event() instanceof PlanModeEvent mode) {
                active = mode.active();
            }
        }
        return active;
    }

    /** 日志已提交的计划模式态。 */
    public boolean active(Session session) {
        return foldActive(session.events());
    }

    /** plan:policy 动态段文本：生效返回指导文，否则空串（assemble 跳过）。 */
    public String sectionFor(Session session) {
        return active(session) ? section : "";
    }
}
