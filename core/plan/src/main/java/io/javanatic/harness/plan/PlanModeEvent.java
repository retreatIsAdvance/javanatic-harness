package io.javanatic.harness.plan;

import io.javanatic.harness.session.event.ExtensionEvent;

/**
 * 计划模式开关（log-only 扩展事件）：自本事件起计划模式是否生效，fold 末值胜、
 * 无事件即 inactive。不参与模型可见投影——提示词经 plan:policy 动态段表达。
 * ignorable=false：plan/mode 影响系统提示词组装，静默丢弃会破坏 R1 可重建性。
 */
public record PlanModeEvent(long time, boolean active) implements ExtensionEvent {

    @Override public String type() { return "plan/mode"; }
}
