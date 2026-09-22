package io.javanatic.harness.examples.headless;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.RequestHeader;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.persistence.jsonl.JsonlPersistencePlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * it22 会话查看面：--sessions 只读列举（无 key 可跑;行内 id 直接可喂 --resume）、
 * 与执行类参数互斥、未知 --resume id 的 exit 3 指引、REPL 首屏会话身份。
 */
class HeadlessSessionsTest {

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    @Test
    void sessionsListsOnDiskFactsWithoutKey() throws Exception {
        fixture("s-old", 3, "/w/old", 1_000);
        fixture("s-new", 4, "/w/new", 3_000);
        // key 环境名不存在:列举在 API key 检查之前收口（无 key 可跑是契约,不是巧合）
        Result result = run("--sessions", "--api-key-env=JH_SESSIONS_TEST_UNSET_KEY");
        assertThat(result.exit()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.lines().getFirst()).isEqualTo("sessions: 2/2   root=" + sessions);
        assertThat(result.stdout())
            .containsPattern("  s-new  created=\\d{4}-\\d{2}-\\d{2}T\\S+  last=\\d{4}-\\d{2}-\\d{2}T\\S+"
                + "  events=4  idle  cwd=/w/new")
            .containsPattern("  s-old  created=\\S+  last=\\S+  events=3  idle  cwd=/w/old");
        assertThat(result.lines().get(1)).startsWith("  s-new"); // 末次活动降序
    }

    @Test
    void sessionsLimitTruncatesWithTailHint() throws Exception {
        fixture("s1", 2, "/w", 1_000);
        fixture("s2", 2, "/w", 2_000);
        fixture("s3", 2, "/w", 3_000);
        Result result = run("--sessions=2");
        assertThat(result.exit()).isZero();
        assertThat(result.lines().getFirst()).isEqualTo("sessions: 2/3   root=" + sessions);
        assertThat(result.lines().get(1)).startsWith("  s3");
        assertThat(result.lines().get(2)).startsWith("  s2");
        assertThat(result.lines().get(3)).contains("截断").contains("共 3").contains("--sessions=<N>");
        assertThat(result.lines()).hasSize(4);
    }

    @Test
    void emptyRootListsNothingAndStaysReadOnly() throws Exception {
        Result result = run("--sessions");
        assertThat(result.exit()).isZero();
        assertThat(result.lines()).containsExactly("sessions: 0/0   root=" + sessions);
    }

    @Test
    void sessionsMutuallyExclusiveWithRunFlagsAndLimitMustBePositive() {
        assertThatThrownBy(() -> HeadlessMain.parse(new String[] {"--sessions", "任务"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--sessions").hasMessageContaining("任务文本");
        assertThatThrownBy(() -> HeadlessMain.parse(new String[] {"--sessions", "--resume=x"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--resume");
        assertThatThrownBy(() -> HeadlessMain.parse(new String[] {"--sessions", "--verify"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--verify");
        assertThatThrownBy(() -> HeadlessMain.parse(new String[] {"--sessions=0"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("正整数");
        assertThatThrownBy(() -> HeadlessMain.parse(new String[] {"--sessions=abc"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("正整数");
        assertThat(HeadlessMain.parse(new String[] {"--sessions"}).sessions().getAsInt())
            .isEqualTo(20);
        assertThat(HeadlessMain.parse(new String[] {"--sessions=5"}).sessions().getAsInt())
            .isEqualTo(5);
        assertThat(HeadlessMain.parse(new String[] {"任务"}).sessions()).isEmpty();
        assertThat(HeadlessMain.USAGE).contains("--sessions[=<N>]").contains("--sessions=<N> 放大上限");
    }

    @Test
    void unknownResumeIdExitsThreeWithRecoveryGuidance() throws Exception {
        Result result = run("--resume=nope", "--api-key=fake");
        assertThat(result.exit()).isEqualTo(3);
        assertThat(result.stdout()).isEmpty();
        assertThat(HeadlessMain.unknownSessionText("nope"))
            .contains("nope").contains("--sessions").contains("--resume=<id>");
    }

    @Test
    void replBannerShowsResumableSessionId() throws Exception {
        Result result = run("--api-key=fake");
        assertThat(result.exit()).isZero();
        Matcher banner = Pattern.compile(
            "session=(headless-[\\w-]+)（续跑: jh --resume=\\1）").matcher(result.stdout());
        assertThat(banner.find()).as("首屏须印会话身份与续跑指引").isTrue();
        // 印出的 id 就是可恢复的会话:盘上目录在场（恢复指引不是装饰文案）
        assertThat(Files.isDirectory(sessions.resolve(banner.group(1)))).isTrue();
    }

    /** 造一个落盘会话:header + 恰 {@code events} 条事件(含 request/header 的 cwd 事实),mtime 定序。 */
    private void fixture(String id, int events, String cwd, long logMillis) throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(sessions)));
            Session session = rt.root().require(SessionStore.KEY)
                .create(rt.root(), Session.newId(id), CreateOptions.empty());
            session.append(new TurnStart(1, 1));
            session.append(new RequestHeader(2, cwd, "2026-09-22"));
            for (int i = 2; i < events; i++) {
                session.append(new TurnStart(i + 1, i + 1));
            }
        }
        Files.setLastModifiedTime(sessions.resolve(id).resolve("log.jsonl"),
            FileTime.fromMillis(logMillis));
    }

    private Result run(String... args) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = HeadlessMain.run(HeadlessMain.parse(args), workspace, sessions,
            new BufferedReader(new StringReader("")),
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(exit, stdout.toString(StandardCharsets.UTF_8),
            stderr.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String stdout, String stderr) {

        List<String> lines() {
            return stdout.lines().toList();
        }
    }
}
