package io.javanatic.harness.workbench.projection;

import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.workbench.projection.TimelineEntry.TimelineDetail;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static io.javanatic.harness.workbench.projection.TestEvents.FakeExtension;
import static io.javanatic.harness.workbench.projection.TestEvents.assistant;
import static io.javanatic.harness.workbench.projection.TestEvents.call;
import static io.javanatic.harness.workbench.projection.TestEvents.compactionEnd;
import static io.javanatic.harness.workbench.projection.TestEvents.compactionStart;
import static io.javanatic.harness.workbench.projection.TestEvents.compactionSummary;
import static io.javanatic.harness.workbench.projection.TestEvents.endSeed;
import static io.javanatic.harness.workbench.projection.TestEvents.instructions;
import static io.javanatic.harness.workbench.projection.TestEvents.llmRequest;
import static io.javanatic.harness.workbench.projection.TestEvents.requestHeader;
import static io.javanatic.harness.workbench.projection.TestEvents.result;
import static io.javanatic.harness.workbench.projection.TestEvents.stepEnd;
import static io.javanatic.harness.workbench.projection.TestEvents.stepStart;
import static io.javanatic.harness.workbench.projection.TestEvents.turnEnd;
import static io.javanatic.harness.workbench.projection.TestEvents.turnStart;
import static io.javanatic.harness.workbench.projection.TestEvents.user;
import static org.assertj.core.api.Assertions.assertThat;

class TimelineViewTest {

    @Test
    void mapsEveryCoreEventToItsDetailVariant() {
        List<SessionEvent> events = List.of(
            requestHeader(0, "/ws", "2026-11-10"),
            instructions(1, "AGENTS.md", "abc123", false, "content"),
            turnStart(2, 1),
            user(3, "hi"),
            stepStart(4, 1, 0),
            llmRequest(5, 1, 0),
            assistant(6, 1, 0, "hello", new TokenUsage(1, 2, 0)),
            call(7, 1, 0, "c1", "read_file", "{}"),
            result(8, 1, 0, "c1", "ok", false),
            stepEnd(9, 1, 0),
            compactionStart(10, 1),
            compactionSummary(11, 1, "sum", 3, 4),
            compactionEnd(12, 1, null),
            turnEnd(13, 1, new TurnEndReason.Completed()),
            endSeed(14)
        );

        List<TimelineEntry> entries = SessionProjection.timeline(events);

        assertThat(entries).hasSize(15);
        for (int i = 0; i < entries.size(); i++) {
            assertThat(entries.get(i).seq()).isEqualTo(i);
            assertThat(entries.get(i).time()).isEqualTo(events.get(i).time());
            assertThat(entries.get(i).type()).isEqualTo(events.get(i).type());
            assertThat(entries.get(i).recognized()).isTrue();
        }

        assertThat(entries.get(0).detail()).isEqualTo(new TimelineDetail.Request("/ws", "2026-11-10"));
        assertThat(entries.get(1).detail())
            .isEqualTo(new TimelineDetail.Instructions("AGENTS.md", "abc123", false, 7));
        assertThat(entries.get(2).detail()).isEqualTo(new TimelineDetail.Turn(1, null));
        assertThat(entries.get(3).detail()).isEqualTo(new TimelineDetail.User("User", 2));
        assertThat(entries.get(4).detail()).isEqualTo(new TimelineDetail.Step(1, 0));
        assertThat(entries.get(5).detail())
            .isEqualTo(new TimelineDetail.LlmRequest(1, 0, 1, 3, Map.of("model", "m")));
        assertThat(entries.get(6).detail())
            .isEqualTo(new TimelineDetail.Assistant(1, 0, 5, new TokenUsage(1, 2, 0)));
        assertThat(entries.get(7).detail())
            .isEqualTo(new TimelineDetail.ToolCall(1, 0, "read_file", "{}"));
        assertThat(entries.get(8).detail())
            .isEqualTo(new TimelineDetail.ToolResult(1, 0, false, 2, false));
        assertThat(entries.get(9).detail()).isEqualTo(new TimelineDetail.Step(1, 0));
        assertThat(entries.get(10).detail()).isEqualTo(new TimelineDetail.CompactionStart(1));
        assertThat(entries.get(11).detail())
            .isEqualTo(new TimelineDetail.CompactionSummary(1, 3, 3, 4, null));
        assertThat(entries.get(12).detail()).isEqualTo(new TimelineDetail.CompactionEnd(1, null));
        assertThat(entries.get(13).detail()).isEqualTo(new TimelineDetail.Turn(1, "completed"));
        assertThat(entries.get(14).detail()).isNull();   // end-seed:已知但负载为空
    }

    @Test
    void unknownExtensionRendersTypeOnlyAndNeverTouchesPayload() {
        Object bomb = new Object() {
            @Override
            public String toString() {
                throw new AssertionError("extension payload must not be read");
            }
        };

        List<TimelineEntry> entries = SessionProjection.timeline(List.of(
            new FakeExtension(100, "jobs", bomb)
        ));

        assertThat(entries).hasSize(1);
        TimelineEntry entry = entries.get(0);
        assertThat(entry.seq()).isEqualTo(0L);
        assertThat(entry.time()).isEqualTo(100L);
        assertThat(entry.type()).isEqualTo("fake/jobs");
        assertThat(entry.recognized()).isFalse();
        assertThat(entry.detail()).isNull();
    }

    @Test
    void interleavedKnownAndUnknownKeepOrder() {
        List<SessionEvent> events = List.of(
            turnStart(0, 1),
            new FakeExtension(1, "skill", "payload"),
            call(2, 1, 0, "c", "t", "{}"),
            new FakeExtension(3, "mcp", "payload")
        );

        List<TimelineEntry> entries = SessionProjection.timeline(events);

        assertThat(entries).extracting(TimelineEntry::recognized)
            .containsExactly(true, false, true, false);
        assertThat(entries).extracting(TimelineEntry::type)
            .containsExactly("turn/start", "fake/skill", "tool/call", "fake/mcp");
    }

    @Test
    void errorCompactionEndCarriesMessage() {
        TimelineEntry entry = SessionProjection.timeline(List.of(
            compactionEnd(0, 2, "boom")
        )).get(0);

        assertThat(entry.detail()).isEqualTo(new TimelineDetail.CompactionEnd(2, "boom"));
    }

    @Test
    void truncatedInstructionsKeepFlag() {
        TimelineEntry entry = SessionProjection.timeline(List.of(
            instructions(0, "AGENTS.md", "sha", true, "cut")
        )).get(0);

        assertThat(entry.detail())
            .isEqualTo(new TimelineDetail.Instructions("AGENTS.md", "sha", true, 3));
    }

    @Test
    void emptyLogYieldsEmptyTimeline() {
        assertThat(SessionProjection.timeline(List.of())).isEmpty();
    }
}
