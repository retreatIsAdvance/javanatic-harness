package io.javanatic.harness.workbench.rpc;

import java.util.Locale;
import java.util.Optional;

/**
 * it37 停 1:B36/B37 读面信任围栏——纯静态函数,零状态零 IO,包内可见
 * 不导出(module-info 不动)。信任层非 auth 层(B29 单用户假设不变):
 * B29 回环恒绑防网络面,本围栏防浏览器面——SOP 只挡读响应不挡发请求,
 * 恶意网页可从用户浏览器向回环端口发 POST。
 *
 * <p>两层防御正交闭合:① Host/Origin(B36)——Host 白名单精确比对实际
 * 绑定端口,Origin 缺失放行(curl/非浏览器)、存在即须同源;② Content-Type
 * 严格性(B37)——CORS 预检只挡非简单请求,form-encoded/text/plain 等
 * 简单请求形态不触发预检可直达,故 POST 仅收 application/json。
 *
 * <p>全部 fail-closed,拒绝给具名理由。已知边缘:若宿主栈回环解析为
 * ::1,Host {@code [::1]:port} 不在白名单 → 403(白名单按裁决钉死两形,
 * 扩面留重访)。
 */
final class RequestFence {

    private RequestFence() {
    }

    /**
     * Host/Origin 围栏。返回拒绝理由;{@link Optional#empty()} = 放行。
     *
     * @param host   Host 头原值(null = 缺失);lowercase 后精确等于
     *               {@code 127.0.0.1:<port>} 或 {@code localhost:<port>}
     * @param origin Origin 头原值(null = 缺失放行);存在即须精确等于
     *               {@code http://127.0.0.1:<port>} 或 {@code http://localhost:<port>}
     *               (lowercase 比较;scheme 仅 http——本地无 TLS)
     * @param port   实际绑定端口(server.getAddress().getPort())
     */
    static Optional<String> hostOriginRejection(String host, String origin, int port) {
        String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (!normalizedHost.equals("127.0.0.1:" + port)
                && !normalizedHost.equals("localhost:" + port)) {
            return Optional.of("host not allowed");
        }
        if (origin != null) {
            String normalizedOrigin = origin.toLowerCase(Locale.ROOT);
            if (!normalizedOrigin.equals("http://127.0.0.1:" + port)
                    && !normalizedOrigin.equals("http://localhost:" + port)) {
                return Optional.of("origin not allowed");
            }
        }
        return Optional.empty();
    }

    /**
     * Content-Type 严格性(B37):媒体类型({@code ;} 前、trim、lowercase)
     * 精确 {@code application/json}——参数(charset 等)放行;缺失/空/
     * form-urlencoded/text/plain/multipart/其余一律拒。
     */
    static boolean jsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        int semi = contentType.indexOf(';');
        String mediaType = (semi < 0 ? contentType : contentType.substring(0, semi))
            .trim()
            .toLowerCase(Locale.ROOT);
        return mediaType.equals("application/json");
    }
}
