/**
 * harness-shell-shell — shell capability seam: ShellExecutor and the
 * ShellRequest/ShellResult wire types; providers swap without touching the
 * seam (05).
 */
module io.javanatic.harness.shell.shell {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.sandbox.sandbox;
    requires io.javanatic.harness.llm.llm;

    exports io.javanatic.harness.shell.shell;
}
