package io.javanatic.harness.delegation.local;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.lang.reflect.Field;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolRegistry;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * 继承快照执法的结构性背书（停 2 评审重点③双路之一）：LocalDelegationService
 * 不持有 scope 注册面——无 Scope 字段、不调 provide / ToolRegistry.register；
 * 子 scope 的唯一触碰点是 DelegationChildSetup（装配期）。另一路 = 扩注探针
 * （停 3 集成 rig 无扩口可用：服务对外仅暴露 seam 六方法）。
 */
class DelegationScopeBoundaryTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .importPackages("io.javanatic.harness.delegation.local");

    @Test
    void serviceNeverRegistersIntoAnyScope() {
        noClasses().that().haveSimpleName("LocalDelegationService")
                .should().callMethod(Scope.class, "provide",
                        ServiceKey.class, Object.class)
                .orShould().callMethod(ToolRegistry.class, "register",
                        Scope.class,
                        ToolDefinition.class)
                .because("子 scope 注册面唯一经 DelegationChildSetup（装配期快照执法）")
                .check(CLASSES);
    }

    @Test
    void serviceHoldsNoChildScopeField() throws Exception {
        for (Field field : LocalDelegationService.class.getDeclaredFields()) {
            if ("ownerScope".equals(field.getName())) {
                continue; // 父装配 scope（agents.create 的 owner 实参）——非子域，扩口由调用规则封死
            }
            assertThat(field.getType().getSimpleName())
                    .as("字段 %s 不得持有任何 scope 视图（装配后无扩口）", field.getName())
                    .doesNotContain("Scope");
        }
    }
}
