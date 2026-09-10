package io.javanatic.harness.boot;

import io.javanatic.harness.agentloop.LoopGuard;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.tools.ApprovalService;

import java.util.ArrayList;
import java.util.List;

/** 策略档位（07 §6；实现自述 + 档位校验拒绝不合格组合，fail loud at boot）。 */
public enum Policy {

    /** 治理已挂载即可（审批任意模式、内存会话可接受）。 */
    STANDARD {
        @Override
        List<String> violations(Scope scope) {
            List<String> violations = new ArrayList<>();
            requireMounted(scope, violations);
            return violations;
        }
    },

    /** 生产档：禁 AUTO 审批、要求耐久持久化、LoopGuard limits 非零。 */
    PRODUCTION {
        @Override
        List<String> violations(Scope scope) {
            List<String> violations = new ArrayList<>();
            requireMounted(scope, violations);
            ApprovalService approval = scope.resolve(ApprovalService.KEY).orElse(null);
            if (approval != null && approval.mode() == ApprovalService.Mode.AUTO) {
                violations.add("policy=PRODUCTION 但审批为 AUTO（换 approval-ask / approval-deny）");
            }
            SessionPersistence persistence = scope.resolve(SessionPersistence.KEY).orElse(null);
            if (persistence != null && !persistence.durable()) {
                violations.add("policy=PRODUCTION 但持久化非耐久（挂 persistence-jsonl）");
            }
            LoopGuard guard = scope.resolve(LoopGuard.KEY).orElse(null);
            if (guard != null && (guard.limits().maxTurns() <= 0 || guard.limits().maxStepsPerTurn() <= 0)) {
                violations.add("policy=PRODUCTION 但 LoopGuard limits 为零");
            }
            if (guard != null && guard.limits().maxBudgetTokens() <= 0) {
                violations.add("policy=PRODUCTION 但 token budget 为零(config loop-guard.maxBudgetTokens)");
            }
            return violations;
        }
    };

    /**
     * @param scope 装配后的根 scope
     * @return 违规项清单（空 = 通过）
     */
    public List<String> check(Scope scope) {
        return violations(scope);
    }

    abstract List<String> violations(Scope scope);

    private static void requireMounted(Scope scope, List<String> violations) {
        if (scope.resolve(ApprovalService.KEY).isEmpty()) {
            violations.add("治理缺失：无 ApprovalService（先装载 approval-* 插件）");
        }
        if (scope.resolve(SessionPersistence.KEY).isEmpty()) {
            violations.add("治理缺失：无 SessionPersistence（装载 persistence-jsonl）");
        }
        if (scope.resolve(LoopGuard.KEY).isEmpty()) {
            violations.add("治理缺失：无 LoopGuard（装载 loop-guard）");
        }
    }
}
