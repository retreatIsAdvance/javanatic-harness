package io.javanatic.harness.workbench.rpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * W4 静态服务 seam(it36 停 1):单文件 .html 随模块资源进镜像,构造期
 * 从 classpath 加载一次、内存缓存字节。缺失即 fail loud——资源是模块
 * 自带,缺失 = 打包错误,不静默服务空页。
 *
 * <p>dispatch 只读消费 {@link #html()}(防御性拷贝,防调用方 mutate
 * 缓存)。路径注入构造为包私有测试面,非生产回调。
 */
public final class WorkbenchUi {

    /** 静态服务响应 Content-Type(路由契约钉死面)。 */
    public static final String CONTENT_TYPE = "text/html; charset=utf-8";

    private static final String DEFAULT_RESOURCE =
        "/io/javanatic/harness/workbench/rpc/ui.html";

    private final byte[] html;

    /** 加载缺省资源;缺失抛 {@link IllegalStateException}(fail loud)。 */
    public WorkbenchUi() {
        this(DEFAULT_RESOURCE);
    }

    WorkbenchUi(String resourcePath) {
        try (InputStream in = WorkbenchUi.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException(
                    "UI 资源缺失: " + resourcePath + "(打包错误,拒绝服务空页)");
            }
            html = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("UI 资源读取失败: " + resourcePath, e);
        }
    }

    /** 缓存字节的防御性拷贝。 */
    public byte[] html() {
        return html.clone();
    }
}
