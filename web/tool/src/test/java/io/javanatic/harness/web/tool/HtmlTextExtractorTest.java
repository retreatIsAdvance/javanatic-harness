package io.javanatic.harness.web.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * HTML 提取器矩阵（T5）：移除块、引号内 {@code >}、大小写、块/行/表格/标题/围栏、
 * 实体（命名/数字/未知）、空白折叠、深层嵌套无递归、源码截断 fail-soft、
 * 真实脏 HTML 样本。断言是行为契约，不是解析器实现细节。
 */
class HtmlTextExtractorTest {

    @Test
    void removesScriptStyleNoscriptTemplateCommentsDoctypeAndCdataCaseInsensitively() {
        String html = """
            <!DOCTYPE html>
            <html><head><title>Page title</title>
            <!-- <p>hidden comment</p> -->
            <SCRIPT type="text/javascript">var x = "<p>fake</p>";</SCRIPT>
            <style>.x { color: red }</style>
            <noscript><p>enable js</p></noscript>
            <template><p>template body</p></template>
            </head><body><![CDATA[secret cdata]]><p>Visible &amp; real</p></body></html>
            """;

        HtmlTextExtractor.Result result = HtmlTextExtractor.extract(html, 10_000);

        assertThat(result.truncated()).isFalse();
        assertThat(result.text())
            .contains("Page title")
            .contains("Visible & real")
            .doesNotContain("hidden comment")
            .doesNotContain("fake")
            .doesNotContain("color: red")
            .doesNotContain("enable js")
            .doesNotContain("template body")
            .doesNotContain("secret cdata");
    }

    @Test
    void ignoresGreaterThanInsideQuotedAttributesAndSurvivesUnclosedTag() {
        String quoted = "<div data-x=\">\" title='a > b'><p>body</p></div>";
        assertThat(HtmlTextExtractor.extract(quoted, 10_000).text()).isEqualTo("body");

        String unclosed = "<p>kept</p><span title=\"never closed";
        assertThatCode(() -> HtmlTextExtractor.extract(unclosed, 10_000)).doesNotThrowAnyException();
        assertThat(HtmlTextExtractor.extract(unclosed, 10_000).text()).isEqualTo("kept");
    }

    @Test
    void handlesMixedCaseTagsAndStrayClosingTags() {
        assertThat(HtmlTextExtractor.extract("<DIV><P>Hi</P></DIV>", 10_000).text())
            .isEqualTo("Hi");
        assertThat(HtmlTextExtractor.extract("</script><p>x</p>", 10_000).text())
            .isEqualTo("x");
    }

    @Test
    void mapsBlockLineBreakTableHeadingAndPreBoundaries() {
        assertThat(HtmlTextExtractor.extract("<p>one</p><p>two</p>", 10_000).text())
            .isEqualTo("one\n\ntwo");
        assertThat(HtmlTextExtractor.extract("<div>a<br>b</div>", 10_000).text())
            .isEqualTo("a\nb");
        assertThat(HtmlTextExtractor.extract("<h2>Head</h2><p>x</p>", 10_000).text())
            .isEqualTo("## Head\n\nx");
        assertThat(HtmlTextExtractor.extract(
            "<ul><li>a</li><li>b</li></ul>", 10_000).text())
            .isEqualTo("- a\n- b");
        assertThat(HtmlTextExtractor.extract(
            "<table><tr><td>a</td><td>b</td></tr><tr><th>c</th><th>d</th></tr></table>",
            10_000).text())
            .isEqualTo("a | b\nc | d");
        assertThat(HtmlTextExtractor.extract("<pre>  x\n    y</pre>", 10_000).text())
            .isEqualTo("```\n  x\n    y\n```");
    }

    @Test
    void decodesFixedNamedEntitiesAndPreservesUnknownOnes() {
        assertThat(HtmlTextExtractor.extract(
            "<p>&amp;&lt;&gt;&quot;&apos;&mdash;&ndash;&hellip;&copy;&reg;</p>", 10_000).text())
            .isEqualTo("&<>\"'—–…©®");
        assertThat(HtmlTextExtractor.extract("<p>&#65;&#x42;&#x1F600;</p>", 10_000).text())
            .isEqualTo("AB\uD83D\uDE00");
        assertThat(HtmlTextExtractor.extract("<p>&unknown;&amp;lt;</p>", 10_000).text())
            .isEqualTo("&unknown;&lt;");
        assertThat(HtmlTextExtractor.extract("<p>&#xD800;</p>", 10_000).text())
            .isEqualTo("&#xD800;");
    }

    @Test
    void collapsesWhitespaceOutsidePreAndPreservesItInside() {
        assertThat(HtmlTextExtractor.extract("<p> a \n\t b   c </p>", 10_000).text())
            .isEqualTo("a b c");
        assertThat(HtmlTextExtractor.extract("<pre>a  b\n  c</pre>", 10_000).text())
            .isEqualTo("```\na  b\n  c\n```");
        assertThat(HtmlTextExtractor.extract("<p>a&nbsp;b</p>", 10_000).text())
            .isEqualTo("a b");
    }

    @Test
    void deepNestingIsLinearAndDoesNotOverflow() {
        String html = "<div>".repeat(50_000) + "deep" + "</div>".repeat(50_000);
        HtmlTextExtractor.Result result = HtmlTextExtractor.extract(html, 10_000_000);
        assertThat(result.text()).isEqualTo("deep");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void emptyAndNonHtmlInputsAreHandled() {
        assertThat(HtmlTextExtractor.extract("", 10_000).text()).isEmpty();
        assertThat(HtmlTextExtractor.extract("plain &amp; text", 10_000).text())
            .isEqualTo("plain & text");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> HtmlTextExtractor.extract("x", 0));
    }

    @Test
    void sourceCapTruncatesMidTagFailSoft() {
        HtmlTextExtractor.Result result =
            HtmlTextExtractor.extract("<p>Hello</p><p>World</p>", 14);

        assertThat(result.truncated()).isTrue();
        assertThat(result.text()).isEqualTo("Hello");
    }

    @Test
    void dirtyRealWorldSampleKeepsEvidenceAndDropsNoise() {
        String html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>Release notes</title>
              <script>if (a > b) { document.write("<p>fake body</p>"); }</script>
              <style>.nav { display: none }</style>
            </head>
            <body>
              <!-- analytics -->
              <nav><a href="https://example.com/a?x=1&amp;y=2">Home</a></nav>
              <article>
                <h1>Article&nbsp;title</h1>
                <p>Body text&hellip; with <strong>markup</strong>.</p>
                <table>
                  <tr><th>Key</th><th>Value</th></tr>
                  <tr><td>alpha</td><td>1</td></tr>
                </table>
                <pre><code>line1
              line2</code></pre>
              </article>
              <script>console.log('<p>also fake</p>')</script>
            </body>
            </html>
            """;

        HtmlTextExtractor.Result result = HtmlTextExtractor.extract(html, 10_000);

        assertThat(result.text())
            .contains("# Article title")
            .contains("Body text… with markup.")
            .contains("Key | Value")
            .contains("alpha | 1")
            .contains("line1")
            .contains("  line2")
            .doesNotContain("fake body")
            .doesNotContain("also fake")
            .doesNotContain("analytics")
            .doesNotContain("display: none");
    }
}
