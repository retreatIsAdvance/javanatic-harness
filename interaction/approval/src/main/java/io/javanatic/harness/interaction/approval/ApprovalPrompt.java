package io.javanatic.harness.interaction.approval;

import io.javanatic.harness.tools.ApprovalService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 一次审批的交互通道：向人呈现请求并等待裁决。默认实现走 headless stdin
 * （只接受显式 y）；组合可注入回调（未来 UI/ACP 接管）。
 * 缺省必须是拒绝语义——EOF/非交互/超时绝不放行。
 */
@FunctionalInterface
public interface ApprovalPrompt {

    /**
     * @param request 审批请求
     * @return true 放行
     */
    boolean ask(ApprovalService.ApprovalRequest request);

    /** headless stdin 默认:打印摘要到 stderr,从 stdin 读一行,仅 y/Y 放行。 */
    static ApprovalPrompt stdin() {
        return request -> {
            System.err.printf("[approval] allow %s %s? [y/N] ", request.toolName(), request.summary());
            String line;
            try {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
                line = reader.readLine();
            } catch (IOException e) {
                return false;
            }
            return line != null && line.trim().equalsIgnoreCase("y");
        };
    }
}
