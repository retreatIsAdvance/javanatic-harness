package io.javanatic.harness.workbench.tui;

import java.io.IOException;

/**
 * 按键输入 seam(停 2-S2)：应用循环只依赖本接口,真实现为 {@link TermIo}
 * 的 JLine 委托,单测用脚本假件。返回语义:字符 ≥ 0;{@link #EOF} 输入结束;
 * {@link #READ_EXPIRED} 超时无输入(定时读面的「空结果」)。两常量与
 * JLine NonBlockingReader 同名常量钉死同值(契约测试实核)。
 */
public interface KeyInput {

    /** 输入流结束。 */
    int EOF = -1;

    /** 定时读超时,无可用输入。 */
    int READ_EXPIRED = -2;

    /** 阻塞读单字符(无定时面)。 */
    int read() throws IOException;

    /** 定时读单字符:至多等 timeoutMillis 毫秒;超时返回 {@link #READ_EXPIRED}。 */
    int read(long timeoutMillis) throws IOException;
}
