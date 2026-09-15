package io.javanatic.harness.interaction.commands;

import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.session.Session;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

/**
 * slash 命令注册表（Definition，dsh commands 形状）：注册即 effect（重复名
 * fail loud，返回注销凭据）；语法 = 行首 {@code /} + 名 {@code [a-z0-9_-]+}
 * （首个非名字符起为 rawInput 原文）。执行落账对 command/run（先于处理器）与
 * command/done（settle，异常路径 ok=false 且原样上抛）——命令是交互面的
 * log-only 事实，不参与模型可见投影。
 */
public final class CommandRegistry {

    /** 本服务的服务键。 */
    public static final ServiceKey<CommandRegistry> KEY = new ServiceKey<>("commands");

    private final Map<String, Command> byName = new ConcurrentHashMap<>();

    /**
     * 注册一条命令。
     *
     * @param command 命令（名语法经 {@link Command} 构造器校验）
     * @throws IllegalStateException 名字已被注册时
     * @return 注销凭据（幂等）
     */
    public Disposable register(Command command) {
        String name = command.name();
        Command existing = byName.putIfAbsent(name, command);
        if (existing != null) {
            throw new IllegalStateException("command already registered: /" + name);
        }
        return Disposable.of(() -> byName.remove(name, command));
    }

    /** @param name 命令名 @return 该名的命令 */
    public Optional<Command> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** @return 全部命令（名升序，/help 输出稳定） */
    public List<Command> list() {
        return byName.values().stream().sorted(Comparator.comparing(Command::name)).toList();
    }

    /**
     * 解析命令行：仅行首 {@code /} 且名过 {@code [a-z0-9_-]+} 才算命令；
     * rawInput = 名字之后的原文（保留空白）。
     *
     * @param line 输入行
     * @return 解析结果；非命令行为 empty
     */
    public static Optional<CommandInvocation> parseCommand(String line) {
        if (!line.startsWith("/")) {
            return Optional.empty();
        }
        Matcher matcher = Command.NAME.matcher(line.substring(1));
        if (!matcher.lookingAt()) {
            return Optional.empty();
        }
        String name = matcher.group();
        return Optional.of(new CommandInvocation(name, line.substring(1 + name.length())));
    }

    /**
     * 执行并落账：command/run 先落、处理器继之、command/done 在 settle
     * （处理器异常也落 done(ok=false) 后原样上抛）。
     *
     * @param invocation 已解析且已解析出命令的命令行
     * @param session 落账目标
     * @throws IllegalStateException 名字未注册（适配器应先 {@link #find}）时
     * @return 处理器结果
     */
    public CommandResult execute(CommandInvocation invocation, Session session) {
        Command command = byName.get(invocation.name());
        if (command == null) {
            throw new IllegalStateException("command not registered: /" + invocation.name());
        }
        session.append(new CommandRunEvent(System.currentTimeMillis(),
            invocation.name(), invocation.rawInput()));
        CommandResult result;
        try {
            result = command.handler().execute(invocation);
        } catch (RuntimeException e) {
            session.append(new CommandDoneEvent(System.currentTimeMillis(),
                invocation.name(), false, describe(e)));
            throw e;
        }
        session.append(new CommandDoneEvent(System.currentTimeMillis(),
            invocation.name(), true, detail(result)));
        return result;
    }

    private static String detail(CommandResult result) {
        return switch (result) {
            case CommandResult.Text text -> text.content();
            case CommandResult.Quit ignored -> "quit";
        };
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + ": " + (message == null ? "" : message);
    }
}
