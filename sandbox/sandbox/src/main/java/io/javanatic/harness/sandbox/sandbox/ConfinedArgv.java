package io.javanatic.harness.sandbox.sandbox;

import java.util.List;
import java.util.Objects;

/**
 * {@link SandboxProvider#confine} 的结果：替代调用方自身 argv 的包装 argv，
 * 加本后端对该策略达到的强制完备度。
 *
 * @param argv              包装后 argv（runner、profile、分隔符、再调用方 argv）
 * @param enforcement       所选后端对本策略文件效果的强制完备度
 * @param denialSignatures  本后端的拒绝<b>方言</b>：文件效果被拒时 stderr 行内
 *                          出现的子串（大小写不敏感）——消费方按后端方言识别
 *                          「沙箱拒绝」，不得用跨后端并集（并集会宣称本后端
 *                          从不产生的拒绝）
 */
public record ConfinedArgv(List<String> argv, SandboxEnforcement enforcement,
                           List<String> denialSignatures) {

    /** @throws NullPointerException 任一字段或 argv 元素为 null 时 */
    public ConfinedArgv {
        Objects.requireNonNull(argv, "argv");
        Objects.requireNonNull(enforcement, "enforcement");
        denialSignatures = List.copyOf(denialSignatures);
        argv = List.copyOf(argv);
        if (argv.isEmpty() || argv.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("argv must be non-empty and null-free");
        }
    }
}
