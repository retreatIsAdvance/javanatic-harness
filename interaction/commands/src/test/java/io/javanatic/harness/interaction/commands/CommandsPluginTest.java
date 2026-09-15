package io.javanatic.harness.interaction.commands;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.jsonl.JsonlPersistencePlugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 命令面插件与 codec：服务提供、事件对经 ServiceLoader 的 jsonl 往返。 */
class CommandsPluginTest {

    @TempDir
    Path root;

    @Test
    void pluginProvidesRegistry() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new CommandsPlugin()));
            assertThat(rt.root().require(CommandRegistry.KEY)).isNotNull();
        }
    }

    @Test
    void codecsRoundTrip() {
        CommandRunCodec runCodec = new CommandRunCodec();
        CommandRunEvent run = new CommandRunEvent(7, "help", " foo");
        assertThat(runCodec.read(runCodec.write(run))).isEqualTo(run);

        CommandDoneCodec doneCodec = new CommandDoneCodec();
        CommandDoneEvent done = new CommandDoneEvent(8, "help", false, "IllegalStateException: kaboom");
        assertThat(doneCodec.read(doneCodec.write(done))).isEqualTo(done);
    }

    @Test
    void commandEventsRoundTripThroughJsonl() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root), new CommandsPlugin()));
            SessionStore store = rt.root().require(SessionStore.KEY);
            Session session = store.create(rt.root(), Session.newId("cmd-durable"), CreateOptions.empty());
            CommandRegistry registry = rt.root().require(CommandRegistry.KEY);
            registry.register(new Command("help", "list commands",
                invocation -> new CommandResult.Text("help text")));

            registry.execute(new CommandInvocation("help", ""), session);
            store.flush(rt.root(), session);

            SessionPersistence.Loaded loaded = rt.root().require(SessionPersistence.KEY).load(session.id());
            assertThat(loaded.events().stream().map(SessionEvent::type).toList())
                .containsExactly("command/run", "command/done");
            CommandDoneEvent done = (CommandDoneEvent) loaded.events().getLast();
            assertThat(done.name()).isEqualTo("help");
            assertThat(done.ok()).isTrue();
            assertThat(done.detail()).isEqualTo("help text");
        }
    }
}
