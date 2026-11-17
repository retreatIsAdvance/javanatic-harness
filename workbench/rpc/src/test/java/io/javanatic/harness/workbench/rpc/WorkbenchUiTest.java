package io.javanatic.harness.workbench.rpc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 静态服务 seam 契约(it36 停 1-S2):缺省资源构造期加载一次、缺失
 * fail loud(打包错误不静默服务空页)、html() 防御性拷贝。资源路径
 * 注入为包私有构造——测试面,非生产回调。
 */
class WorkbenchUiTest {

    @Test
    void defaultResourceLoadsNonEmptyHtmlWithMarker() {
        WorkbenchUi ui = new WorkbenchUi();

        assertThat(ui.html()).isNotEmpty();
        assertThat(new String(ui.html(), StandardCharsets.UTF_8))
            .contains("javanatic-workbench-ui");
        assertThat(WorkbenchUi.CONTENT_TYPE).isEqualTo("text/html; charset=utf-8");
    }

    @Test
    void missingResourceFailsLoud() {
        assertThatThrownBy(() ->
            new WorkbenchUi("/io/javanatic/harness/workbench/rpc/does-not-exist.html"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void htmlReturnsDefensiveCopy() {
        WorkbenchUi ui = new WorkbenchUi();

        byte[] first = ui.html();
        byte original = first[0];
        first[0] = 0;

        assertThat(ui.html()[0]).isEqualTo(original);
    }

    // ────────── it36 停 2 资源内容守卫(功能面回归钉子,非浏览器测试替代品) ──────────

    @Test
    void uiHtmlWiresAllFiveRpcMethods() {
        String html = new String(new WorkbenchUi().html(), StandardCharsets.UTF_8);

        assertThat(html)
            .contains("workbench/sessionsList")
            .contains("workbench/sessionViews")
            .contains("workbench/sessionTail")
            .contains("workbench/compositionDump")
            .contains("workbench/verifyComposition");
    }

    @Test
    void uiHtmlHasFunctionalSurfaceElements() {
        String html = new String(new WorkbenchUi().html(), StandardCharsets.UTF_8);

        // 页签 / 清单 / 五视图子页签 / tail 开关 / 编辑器三按钮 + 文本域 + 结果区
        assertThat(html)
            .contains("id=\"tab-sessions\"")
            .contains("id=\"tab-composition\"")
            .contains("id=\"session-list\"")
            .contains("id=\"view-turns\"")
            .contains("id=\"view-tools\"")
            .contains("id=\"view-usage\"")
            .contains("id=\"view-timeline\"")
            .contains("id=\"view-messages\"")
            .contains("id=\"tail-toggle\"")
            .contains("id=\"composition-editor\"")
            .contains("id=\"load-dump\"")
            .contains("id=\"verify-composition\"")
            .contains("id=\"download-profile\"")
            .contains("id=\"verify-result\"");
    }

    @Test
    void uiHtmlDownloadsViaBlobNotServer() {
        String html = new String(new WorkbenchUi().html(), StandardCharsets.UTF_8);

        // T3 增强:浏览器端落盘(Blob + download 属性),不经 server 写面
        assertThat(html).contains("Blob").contains("download");
        // 安全面:零 innerHTML(动态内容一律 textContent/createElement)
        assertThat(html).doesNotContain("innerHTML");
    }
}
