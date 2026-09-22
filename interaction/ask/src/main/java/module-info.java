/**
 * harness-interaction-ask — ask_user（澄清问答）Provider：与审批分离的问答通道。
 * 工具面声明免审批（ofExempt：提问不是副作用，批准提问等于双重交互，deny 档下
 * 仍可澄清）与停轮结果（concludesTurn：轮在该 step 后收口，答复 = 下一轮
 * user message——问答两半都是日志事实，R1 成立）。见 05 §工具契约。
 */
module io.javanatic.harness.interaction.ask {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.core.tools;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.interaction.ask.AskUserPlugin;

    exports io.javanatic.harness.interaction.ask;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.interaction.ask;
}
