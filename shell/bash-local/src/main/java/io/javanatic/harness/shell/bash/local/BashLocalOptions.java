package io.javanatic.harness.shell.bash.local;

/**
 * bash-local 的 provider 选项。
 *
 * @param maxOutputBytes 单流（stdout/stderr 各自）捕获上限，超出截断并置标记；
 *                       防止超大输出在截断瀑布前先 OOM provider
 */
public record BashLocalOptions(long maxOutputBytes) {

    /** @throws IllegalArgumentException 上限非正时 */
    public BashLocalOptions {
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive: " + maxOutputBytes);
        }
    }
}
