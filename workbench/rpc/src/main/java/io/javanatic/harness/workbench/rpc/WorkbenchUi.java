package io.javanatic.harness.workbench.rpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * W4 静态服务 seam(it36 停 1):单文件 .html 随模块资源进镜像,构造期
 * 从 classpath 加载一次、内存缓存字节。缺失即 fail loud——资源是模块
 * 自带,缺失 = 打包错误,不静默服务空页。
 *
 * <p>it38 停 2 写面形状:同一份资源双模式服务。资源在脚本首行埋哨兵
 * {@value #WRITE_TOKEN}(JS 字面量位置),构造期按 {@code webWrite} 一次性替换
 * 为 {@code true|false} 并缓存——只读面不初始化聊天/审批/SSE,写面才出现
 * 四个写方法与事件流控件。哨兵缺失或重复 = 资源漂移,同样 fail loud。
 *
 * <p>dispatch 只读消费 {@link #html()}(防御性拷贝,防调用方 mutate
 * 缓存)。路径注入构造为包私有测试面,非生产回调。
 */
public final class WorkbenchUi {

    /** 静态服务响应 Content-Type(路由契约钉死面)。 */
    public static final String CONTENT_TYPE = "text/html; charset=utf-8";

    /** 写面模式哨兵:资源里恰好出现一次,服务期替换为 JS 布尔字面量。 */
    static final String WRITE_TOKEN = "__JH_WEB_WRITE__";

    private static final String DEFAULT_RESOURCE =
        "/io/javanatic/harness/workbench/rpc/ui.html";

    private final byte[] html;

    /** 只读面(0.5.0 形状):加载缺省资源;缺失抛 {@link IllegalStateException}(fail loud)。 */
    public WorkbenchUi() {
        this(false);
    }

    /** 按写面开关加载缺省资源并替换模式哨兵。 */
    public WorkbenchUi(boolean webWrite) {
        this(DEFAULT_RESOURCE, webWrite);
    }

    WorkbenchUi(String resourcePath) {
        this(resourcePath, false);
    }

    WorkbenchUi(String resourcePath, boolean webWrite) {
        String source;
        try (InputStream in = WorkbenchUi.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException(
                    "UI 资源缺失: " + resourcePath + "(打包错误,拒绝服务空页)");
            }
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("UI 资源读取失败: " + resourcePath, e);
        }
        int first = source.indexOf(WRITE_TOKEN);
        if (first < 0) {
            throw new IllegalStateException(
                "UI 资源缺少写面模式哨兵: " + WRITE_TOKEN + "(资源漂移,拒绝服务)");
        }
        if (source.indexOf(WRITE_TOKEN, first + WRITE_TOKEN.length()) >= 0) {
            throw new IllegalStateException(
                "UI 资源写面模式哨兵重复: " + WRITE_TOKEN + "(资源漂移,拒绝服务)");
        }
        html = source.replace(WRITE_TOKEN, webWrite ? "true" : "false")
            .getBytes(StandardCharsets.UTF_8);
    }

    /** 缓存字节的防御性拷贝。 */
    public byte[] html() {
        return html.clone();
    }
}
