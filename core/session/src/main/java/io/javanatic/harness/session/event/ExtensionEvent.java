package io.javanatic.harness.session.event;

/**
 * 插件扩展的事件类型出口。核心 switch 用显式分支处理它。
 *
 * <p>扩展插件实现此接口（不继承核心 record），并在插件加载时注册序列化能力
 * （codec 属持久化 seam）。{@code ignorable()} 由扩展实现决定：信息性事件
 * 返回 true（未知读取方跳过），结构性事件返回 false（未知读取方拒绝重建）。
 * 扩展事件若产生模型可见消息，可同时实现 {@link SurfaceEvent} 参与投影。
 */
public non-sealed interface ExtensionEvent extends SessionEvent {
    // 扩展自由实现；type() 必须返回稳定的字符串 key
}
