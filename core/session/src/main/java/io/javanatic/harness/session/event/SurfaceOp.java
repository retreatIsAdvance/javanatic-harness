package io.javanatic.harness.session.event;

/**
 * 事件如何进入有序 surface：
 * <ul>
 *   <li>{@link Append}：尾追加（普通 user/assistant 消息）</li>
 *   <li>{@link Replace}：替换 surface 中 {@code [start, end]}（含端点）的节点，
 *       用于 compaction 摘要；两端的 seq 必须都是当前 surface 节点</li>
 * </ul>
 */
public sealed interface SurfaceOp permits SurfaceOp.Append, SurfaceOp.Replace {

    /** 尾追加。 */
    record Append() implements SurfaceOp {}

    /** 替换一段（含端点）既有 surface 节点，以本事件为新节点。 */
    record Replace(long start, long end) implements SurfaceOp {}
}
