package io.javanatic.harness.interaction.commands;

import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.LoggedEvent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 命令面契约：注册即 effect（重复 fail loud / 注销可再注册）、解析语法
 * （行首 /、名语法、rawInput 原文）、执行落账序（run 先于处理器、done 在
 * settle，异常也落且原样上抛）。
 */
class CommandRegistryTest {

    private static Command command(String name, Command.Handler handler) {
        return new Command(name, "test command", handler);
    }

    private static Session session(String name) {
        return Session.create(Session.newId(name), null, null);
    }

    @Test
    void registerRejectsDuplicateAndDisposeAllowsReregister() {
        CommandRegistry registry = new CommandRegistry();
        Disposable first = registry.register(command("help", invocation -> new CommandResult.Text("ok")));

        assertThatThrownBy(() -> registry.register(
            command("help", invocation -> new CommandResult.Text("again"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("command already registered: /help");

        first.close();
        registry.register(command("help", invocation -> new CommandResult.Text("ok")));
        assertThat(registry.find("help")).isPresent();
    }

    @Test
    void commandNameGrammarIsEnforcedAtConstruction() {
        assertThatThrownBy(() -> command("Help", invocation -> new CommandResult.Text("x")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("[a-z0-9_-]+");
        assertThatThrownBy(() -> new Command("help", "  ", invocation -> new CommandResult.Text("x")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("summary");
    }

    @Test
    void listIsSortedByName() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("zeta", invocation -> new CommandResult.Text("z")));
        registry.register(command("alpha", invocation -> new CommandResult.Text("a")));
        assertThat(registry.list()).extracting(Command::name).containsExactly("alpha", "zeta");
    }

    @Test
    void parseCommandGrammar() {
        assertThat(CommandRegistry.parseCommand("hello")).isEmpty();
        assertThat(CommandRegistry.parseCommand(" /help")).isEmpty();
        assertThat(CommandRegistry.parseCommand("/")).isEmpty();
        assertThat(CommandRegistry.parseCommand("/Help")).isEmpty();
        assertThat(CommandRegistry.parseCommand("/help")).contains(new CommandInvocation("help", ""));
        assertThat(CommandRegistry.parseCommand("/help  foo bar"))
            .contains(new CommandInvocation("help", "  foo bar"));
        assertThat(CommandRegistry.parseCommand("/plan-mode"))
            .contains(new CommandInvocation("plan-mode", ""));
    }

    @Test
    void executeLogsRunBeforeHandlerAndDoneAtSettle() {
        CommandRegistry registry = new CommandRegistry();
        Session session = session("cmd-order");
        AtomicBoolean runVisibleInHandler = new AtomicBoolean();
        List<String> seen = new ArrayList<>();
        registry.register(command("probe", invocation -> {
            runVisibleInHandler.set(session.events().stream()
                .anyMatch(entry -> entry.event() instanceof CommandRunEvent));
            seen.add(invocation.rawInput());
            return new CommandResult.Text("done" + invocation.rawInput());
        }));

        CommandResult result = registry.execute(new CommandInvocation("probe", " x"), session);

        assertThat(runVisibleInHandler).isTrue();
        assertThat(seen).containsExactly(" x");
        assertThat(result).isEqualTo(new CommandResult.Text("done x"));
        assertThat(session.events().stream().map(LoggedEvent::type))
            .containsExactly("command/run", "command/done");
        CommandDoneEvent done = (CommandDoneEvent) session.events().getLast().event();
        assertThat(done.ok()).isTrue();
        assertThat(done.detail()).isEqualTo("done x");
    }

    @Test
    void executeLogsFailedDoneAndRethrows() {
        CommandRegistry registry = new CommandRegistry();
        Session session = session("cmd-fail");
        registry.register(command("boom", invocation -> {
            throw new IllegalStateException("kaboom");
        }));

        assertThatThrownBy(() -> registry.execute(new CommandInvocation("boom", ""), session))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("kaboom");

        assertThat(session.events().stream().map(LoggedEvent::type))
            .containsExactly("command/run", "command/done");
        CommandDoneEvent done = (CommandDoneEvent) session.events().getLast().event();
        assertThat(done.ok()).isFalse();
        assertThat(done.detail()).contains("IllegalStateException").contains("kaboom");
    }

    @Test
    void executeRejectsUnregisteredInvocation() {
        CommandRegistry registry = new CommandRegistry();
        assertThatThrownBy(() -> registry.execute(new CommandInvocation("nope", ""), session("cmd-none")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("command not registered: /nope");
    }

    @Test
    void quitResultPassesThrough() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("exit", invocation -> new CommandResult.Quit()));
        assertThat(registry.execute(new CommandInvocation("exit", ""), session("cmd-quit")))
            .isEqualTo(new CommandResult.Quit());
    }
}
