package io.javanatic.harness.workbench.rpc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

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

    // ────────── it38 停 3 呈现面：dsh 式三栏壳（结构钉子，检视脚本同源） ──────────

    @Test
    void uiHtmlShipsThreeColumnWorkbenchShell() {
        String html = new String(new WorkbenchUi(true).html(), StandardCharsets.UTF_8);

        // 三栏骨架 = 左会话栏 / 中主区 / 右审批与上下文；导航三向仍在左栏
        assertThat(html)
            .contains("id=\"app-shell\"")
            .contains("id=\"sidebar\"")
            .contains("id=\"main\"")
            .contains("id=\"rightbar\"")
            .contains("id=\"side-nav\"")
            .contains("id=\"tab-chat\"")
            .contains("id=\"tab-sessions\"")
            .contains("id=\"tab-composition\"");
        // 右栏承载审批卡片与当前会话上下文（元素 id 不变，位置迁移）
        assertThat(html.indexOf("id=\"rightbar\""))
            .isLessThan(html.indexOf("id=\"approval-overlay\""));
        assertThat(html.indexOf("id=\"approval-overlay\""))
            .isLessThan(html.indexOf("id=\"chat-state\""));
    }

    /** it38 停 3 用户实录修正:ask_user 是数据驱动停轮, turn/end 恒 completed——
     * 徽标不得把「等待答复」(exit 5 语义)覆盖成「已收尾」; 以 concludesTurn 事实位为准。 */
    @Test
    void uiKeepsWaitingAnswerAfterQuestionDriveStopTurnEnd() {
        String html = new String(new WorkbenchUi(true).html(), StandardCharsets.UTF_8);
        assertThat(html)
            .contains("chat.questionPending = true;")
            .contains("setChatState(chat.questionPending ? \"waiting-answer\" : \"done\");");
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

    // ────────── it38 停 2 写面资源形状(服务期哨兵替换,单文件双模式) ──────────

    @Test
    void writeModeSentinelIsSubstitutedAndNeverLeaks() {
        String readOnly = new String(new WorkbenchUi().html(), StandardCharsets.UTF_8);
        String webWrite = new String(new WorkbenchUi(true).html(), StandardCharsets.UTF_8);

        assertThat(readOnly).contains("const WEB_WRITE = false;").doesNotContain("__JH_WEB_WRITE__");
        assertThat(webWrite).contains("const WEB_WRITE = true;").doesNotContain("__JH_WEB_WRITE__");
    }

    @Test
    void uiHtmlWiresChatWriteSurfaceAndSse() {
        String html = new String(new WorkbenchUi(true).html(), StandardCharsets.UTF_8);

        assertThat(html)
            .contains("session/start").contains("session/send").contains("session/cancel")
            .contains("approval/respond")
            .contains("/events?session=")
            .contains("new EventSource")
            .contains("id=\"tab-chat\"")
            .contains("id=\"pane-chat\"")
            .contains("id=\"chat-log\"")
            .contains("id=\"chat-input\"")
            .contains("id=\"chat-send\"")
            .contains("id=\"chat-cancel\"")
            .contains("id=\"chat-state\"")
            .contains("id=\"approval-overlay\"")
            .contains("id=\"approval-list\"")
            .contains("concludesTurn");
    }

    @Test
    void uiHtmlGuardsImeCompositionBeforeEnterHandling() {
        String html = new String(new WorkbenchUi(true).html(), StandardCharsets.UTF_8);

        // IME 组词中按 Enter 不得触发发送/审批：输入框与文档两处 keydown 恒同款守卫
        String guard = "if (event.isComposing || event.keyCode === 229) { return; }";
        assertThat(html.split(Pattern.quote(guard), -1)).hasSize(3);
        assertThat(html).contains("input.addEventListener(\"keydown\"")
            .contains("document.addEventListener(\"keydown\"");
    }

    @Test
    void readOnlyPageHidesWriteSurfacesByCssAndNeverInitsChat() {
        String html = new String(new WorkbenchUi().html(), StandardCharsets.UTF_8);

        // 缺省 body 无 web-write 类 → 聊天页签/面板/审批条 display:none;
        // 写面初始化只在 WEB_WRITE 为真时执行(只读面零交互写控件、零 SSE 连接)。
        assertThat(html)
            .contains("body:not(.web-write) #tab-chat")
            .contains("body:not(.web-write) #pane-chat")
            .contains("body:not(.web-write) #approval-overlay")
            .contains("if (WEB_WRITE) {")
            .contains("initChat();");
    }
}
