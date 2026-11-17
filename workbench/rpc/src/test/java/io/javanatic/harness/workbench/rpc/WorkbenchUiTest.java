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
}
