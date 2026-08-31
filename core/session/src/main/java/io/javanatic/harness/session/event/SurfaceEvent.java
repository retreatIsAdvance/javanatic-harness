package io.javanatic.harness.session.event;

import java.util.List;

/**
 * 产生 LLM 消息的事件子集（标记接口，不继承 {@code SessionEvent}——sealed 联合
 * 的 permits 之外另立出口）：事件同时实现两者才进入有序 surface，编译期保证
 * 只有它们携带 surface 元数据。核心实现是消息事件；扩展事件实现
 * {@code ExtensionEvent} + 本接口即可参与投影（对应 dsh 的 SurfaceEventType
 * 运行时集合）。
 */
public interface SurfaceEvent {

    /** 该事件如何进入有序 surface（追加 / 替换一段）。 */
    SurfaceOp surfaceOp();

    /**
     * 引用的来源事件 seq 集合（provenance）。null = 不记录来源；
     * {@code AssistantMessageEvent} 允许显式空列表（已知空流），其余事件
     * 提供时必须非空。Replace 时必须覆盖全部被替换节点（校验在 SurfaceManager）。
     */
    List<Long> sourceEventSeqs();
}
