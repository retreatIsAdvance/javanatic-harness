package io.javanatic.harness.jobs;

/**
 * 输出读取切片。text 为 [fromByte, nextFromByte) 区间的已捕获输出（UTF-8 完整字符边界，
 * 尾字节不完整时留待下片）；outputTruncated 表示捕获已达上限、后续读取不会有更多字节。
 * endOfCapture = nextFromByte 达 capturedBytes——消费方以此区分「暂时没有更多」与「读尽」。
 */
public record JobSlice(String text, long nextFromByte, long capturedBytes, boolean outputTruncated) {

    /** 切片即校验：text 在场、游标区间非负且不越过捕获上界。 */
    public JobSlice {
        if (text == null) {
            throw new IllegalArgumentException("text must be present");
        }
        if (nextFromByte < 0 || capturedBytes < 0 || nextFromByte > capturedBytes) {
            throw new IllegalArgumentException("cursor bounds violated: nextFromByte/capturedBytes");
        }
    }

    /** 读尽已捕获输出（区别于捕获被截断）。 */
    public boolean endOfCapture() {
        return nextFromByte >= capturedBytes;
    }
}
