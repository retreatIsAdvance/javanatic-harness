package io.javanatic.harness.workbench.projection;

import io.javanatic.harness.session.message.TokenUsage;

import java.util.Map;

/**
 * timeline 视图的一行:事件信封摘要 + 结构化细节(全事件,按日志序)。
 *
 * <p>{@code recognized=false} 表示投影层不认识该事件(ExtensionEvent)——按
 * type 字符串渲染、不解码、负载不进输出,{@code detail} 恒为 null(边界声明:
 * 投影层只依赖核心事件 record + 类型字符串)。
 *
 * @param seq        日志下标(信封 seq,恒等于传入列表下标)
 * @param time       事件时间(Unix epoch 毫秒)
 * @param type       事件类型名(持久化 key)
 * @param recognized 是否核心事件;false = 未知扩展,detail 恒 null
 * @param detail     已知核心事件的结构化细节;负载为空的已知事件
 *                   (session/end-seed)与未知扩展为 null
 */
public record TimelineEntry(long seq, long time, String type, boolean recognized,
                            TimelineDetail detail) {

    /**
     * 已知核心事件的结构化细节(负载为空的已知事件与未知扩展不产生 detail)。
     * 封闭联合:变体全部嵌套在本文件,permits 按 JLS 由编译器从同一编译单元
     * 推断(显式 permits 无法前向引用同文件后声明的嵌套类型)——switch 穷尽
     * 检查不变。
     */
    public sealed interface TimelineDetail {

        /** turn/start(reason=null)与 turn/end(reason=结束原因渲染串)。 */
        record Turn(int turn, String reason) implements TimelineDetail {
        }

        /** step/start 与 step/end。 */
        record Step(int turn, int step) implements TimelineDetail {
        }

        /** user/message:来源简单类名 + 文本字符数。 */
        record User(String source, int contentChars) implements TimelineDetail {
        }

        /** assistant/message:文本字符数 + token 计量(usage=null 未报告)。 */
        record Assistant(int turn, int step, int contentChars, TokenUsage usage)
                implements TimelineDetail {
        }

        /** llm/request:消息窗口区间(闭区间)+ 请求参数。 */
        record LlmRequest(int turn, int step, long from, long to, Map<String, String> params)
                implements TimelineDetail {
        }

        /** tool/call:工具名 + 原始 arguments JSON。 */
        record ToolCall(int turn, int step, String name, String arguments)
                implements TimelineDetail {
        }

        /** tool/result:isError + 内容字符数 + 是否终结轮。 */
        record ToolResult(int turn, int step, boolean isError, int contentChars,
                          boolean concludesTurn) implements TimelineDetail {
        }

        /** compaction/start。 */
        record CompactionStart(int turn) implements TimelineDetail {
        }

        /** compaction/summary:摘要字符数 + 被盖写区间(位置区间)+ 摘要调用计量(可 null)。 */
        record CompactionSummary(int turn, int summaryChars, long shadowedStart,
                                 long shadowedEnd, TokenUsage usage) implements TimelineDetail {
        }

        /** compaction/end(error=null 成功)。 */
        record CompactionEnd(int turn, String error) implements TimelineDetail {
        }

        /** request/header:工作区目录(可空)+ ISO-8601 日期。 */
        record Request(String cwd, String date) implements TimelineDetail {
        }

        /** project/instructions:路径 + 内容哈希 + 截断标记 + 内容字符数。 */
        record Instructions(String path, String sha256, boolean truncated,
                            int contentChars) implements TimelineDetail {
        }
    }
}
