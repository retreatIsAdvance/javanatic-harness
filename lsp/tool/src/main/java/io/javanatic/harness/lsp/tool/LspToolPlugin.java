package io.javanatic.harness.lsp.tool;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.lsp.LspDiagnostic;
import io.javanatic.harness.lsp.LspException;
import io.javanatic.harness.lsp.LspLocation;
import io.javanatic.harness.lsp.LspPosition;
import io.javanatic.harness.lsp.LspPositionQuery;
import io.javanatic.harness.lsp.LspQuery;
import io.javanatic.harness.lsp.LspResult;
import io.javanatic.harness.lsp.LspService;
import io.javanatic.harness.tools.RenderIntent;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ValueSchema;

/**
 * LSP 三能力消费面（插件 id "lsp-tool"，it29 停 3）：{@link LspService} 在场才
 * 注册 `lsp_diagnostics`（GENERIC）与 `lsp_definition` / `lsp_references`
 * （LOCATIONS）——缺省组合零 LSP 工具、零进程、零网络（沿 web_search 双态先例，
 * 无运行时降级）。三工具恒需审批（驱动可信外部进程 + 读项目源码，无豁免出口）。
 *
 * <p>结果文本由 {@link LspToolOutput} 逐字渲染（空结果只称「拉取时点无诊断 /
 * 本次请求返回 0 个位置」）；{@link LspException} 转带稳定码的 error 结果；
 * 取消（{@code AbortedException}）原样穿透。零新事件：审计 = executor 的
 * tool/call + tool/result。位置编码 0 基 UTF-16（{@link LspPosition} 契约）。
 */
public final class LspToolPlugin implements Plugin {

    private static final String DIAGNOSTICS_DESCRIPTION =
        "对项目内文档执行 LSP 诊断观察：返回 JH 拉取时点观察（含项目锚）与诊断列表。"
        + "空结果只表示拉取时点无诊断，不构成任何全局正确性承诺。";

    private static final String DEFINITION_DESCRIPTION =
        "在项目内文档的 0 基 UTF-16 位置解析真实 LSP 定义：返回拉取时点观察（含项目锚）"
        + "与位置列表。空结果只表示本次请求返回 0 个位置。";

    private static final String REFERENCES_DESCRIPTION =
        "在项目内文档的 0 基 UTF-16 位置解析真实 LSP 引用（含声明）：返回拉取时点观察"
        + "（含项目锚）与位置列表。空结果只表示本次请求返回 0 个位置。";

    @Override
    public String id() {
        return "lsp-tool";
    }

    @Override
    public Set<String> requires() {
        return Set.of("tools");
    }

    @Override
    public void apply(Scope scope) {
        LspToolConfig config = scope.resolve(ConfigService.KEY)
            .map(service -> LspToolConfig.parse(service.configFor(id()), id()))
            .orElseGet(LspToolConfig::defaults);
        ToolRegistry registry = scope.require(ToolRegistry.KEY);
        scope.resolve(LspService.KEY).ifPresent(service -> {
            scope.onClose(registry.register(scope, diagnosticsTool(service, config)));
            scope.onClose(registry.register(scope, definitionTool(service, config)));
            scope.onClose(registry.register(scope, referencesTool(service, config)));
        });
    }

    private static ToolDefinition diagnosticsTool(LspService service, LspToolConfig config) {
        ValueSchema schema = new ValueSchema.Object("参数", Map.of(
            "document", new ValueSchema.Str("项目内文档的绝对路径"),
            "timeoutSeconds", new ValueSchema.Opt(new ValueSchema.Num("总期限秒数（缺省取行配置）")),
            "waitSeconds", new ValueSchema.Opt(new ValueSchema.Num("观察等待窗秒数（缺省取行配置）"))));
        return ToolDefinition.of("lsp_diagnostics", DIAGNOSTICS_DESCRIPTION, schema,
            (args, context) -> {
                long timeout = args.readOptionalLong("timeoutSeconds").orElse(config.timeoutSeconds());
                long wait = args.readOptionalLong("waitSeconds").orElse(config.waitSeconds());
                if (timeout < 1 || wait < 1) {
                    return ToolExecutionResult.error(
                        "参数错误（timeoutSeconds 与 waitSeconds 必须为正整数）");
                }
                if (wait > timeout) {
                    return ToolExecutionResult.error("参数错误（waitSeconds 不得超过 timeoutSeconds）");
                }
                try {
                    LspQuery query = new LspQuery(Path.of(args.readString("document")).toUri(),
                        Duration.ofSeconds(timeout), Duration.ofSeconds(wait));
                    LspResult<LspDiagnostic> result =
                        service.diagnostics(query, context.signal());
                    return ToolExecutionResult.success(LspToolOutput.diagnostics(result, config));
                } catch (LspException e) {
                    return ToolExecutionResult.error(LspToolOutput.error(e));
                } catch (IllegalArgumentException e) {
                    return ToolExecutionResult.error("参数错误（" + e.getMessage() + "）");
                }
            });
    }

    private static ToolDefinition definitionTool(LspService service, LspToolConfig config) {
        return locationTool("lsp_definition", DEFINITION_DESCRIPTION, service, config,
            service::definition);
    }

    private static ToolDefinition referencesTool(LspService service, LspToolConfig config) {
        return locationTool("lsp_references", REFERENCES_DESCRIPTION, service, config,
            service::references);
    }

    private static ToolDefinition locationTool(String name, String description, LspService service,
                                               LspToolConfig config, LspLocate locate) {
        ValueSchema schema = new ValueSchema.Object("参数", Map.of(
            "document", new ValueSchema.Str("项目内文档的绝对路径"),
            "line", new ValueSchema.Num("请求位置的行号（0 基，UTF-16）"),
            "column", new ValueSchema.Num("请求位置的字符偏移（0 基，UTF-16）")));
        return new ToolDefinition(name, description, schema, RenderIntent.LOCATIONS,
            (args, context) -> {
                try {
                    LspQuery query = new LspQuery(Path.of(args.readString("document")).toUri(),
                        Duration.ofSeconds(config.timeoutSeconds()),
                        Duration.ofSeconds(config.waitSeconds()));
                    LspPositionQuery position = new LspPositionQuery(query,
                        new LspPosition((int) args.readLong("line"), (int) args.readLong("column")));
                    LspResult<LspLocation> result = locate.run(position, context.signal());
                    return ToolExecutionResult.success(LspToolOutput.locations(result, config));
                } catch (LspException e) {
                    return ToolExecutionResult.error(LspToolOutput.error(e));
                } catch (IllegalArgumentException e) {
                    return ToolExecutionResult.error("参数错误（" + e.getMessage() + "）");
                }
            }, false);
    }

    /** definition / references 共形：同一观察预算、同一错误面。 */
    private interface LspLocate {
        LspResult<LspLocation> run(LspPositionQuery query, AbortSignal signal);
    }
}
