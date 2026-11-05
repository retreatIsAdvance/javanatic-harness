package io.javanatic.harness.jobs.tool;

import io.javanatic.harness.jobs.Job;
import io.javanatic.harness.jobs.JobException;
import io.javanatic.harness.jobs.JobNotFoundException;
import io.javanatic.harness.jobs.JobRequest;
import io.javanatic.harness.jobs.JobService;
import io.javanatic.harness.jobs.JobSnapshot;
import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ValueSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 后台任务消费面（插件 id "jobs-tool"，it30 停 3）：{@link JobService} 在场才注册
 * job_start / job_status / job_read / job_cancel——缺省组合零工具零任务零噪声
 * （沿 lsp/tool 双态先例）。四工具恒需审批（起进程 / 终止进程均副作用，读面同批
 * 治理，B14 同口径，无豁免出口）。工作目录由调用方显式给绝对路径（与 shell 工具
 * 同立场：执行边界无默认）；超时 / 上限缺省 300s / 256 KiB（B30/B31 量纲）。
 * 结果文本由 {@link JobToolOutput} 逐字渲染（三态措辞纪律，禁词突变必红）。
 */
public final class JobsToolPlugin implements Plugin {

    private static final long DEFAULT_TIMEOUT_SECONDS = 300L;
    private static final long DEFAULT_OUTPUT_CAP_BYTES = 262144L;
    private static final long POLL_MILLIS = 200L;

    private static final String START_DESCRIPTION =
        "在后台启动一条 shell 命令并立即返回任务快照。长任务（构建/测试类）用本工具起跑，"
        + "之后用 job_status 轮询、job_read 按游标读输出、job_cancel 取消。";

    private static final String STATUS_DESCRIPTION =
        "查询后台任务快照；可带 waitSeconds 在期限内等它进入终局后再返回。";

    private static final String READ_DESCRIPTION =
        "按字节游标读取后台任务的已捕获输出（UTF-8 完整边界切片）。首读 fromByte=0；"
        + "每次返回下一游标，循环读到读尽。运行中任务读尽只是暂无新输出，不是任务没有输出。";

    private static final String CANCEL_DESCRIPTION =
        "取消运行中的后台任务（TERM→KILL 收敛）；终局任务返回 false 不复活。";

    @Override
    public String id() {
        return "jobs-tool";
    }

    @Override
    public Set<String> requires() {
        return Set.of("tools");
    }

    @Override
    public void apply(Scope scope) {
        JobsToolConfig config = scope.resolve(ConfigService.KEY)
                .map(service -> JobsToolConfig.parse(service.configFor(id()), id()))
                .orElseGet(JobsToolConfig::defaults);
        ToolRegistry registry = scope.require(ToolRegistry.KEY);
        scope.resolve(JobService.KEY).ifPresent(service -> {
            scope.onClose(registry.register(scope, startTool(service)));
            scope.onClose(registry.register(scope, statusTool(service)));
            scope.onClose(registry.register(scope, readTool(service, config)));
            scope.onClose(registry.register(scope, cancelTool(service)));
        });
    }

    private static ToolDefinition startTool(JobService service) {
        ValueSchema schema = new ValueSchema.Object("参数", Map.of(
                "command", new ValueSchema.Str("shell 命令（非空）"),
                "cwd", new ValueSchema.Str("工作目录绝对路径（执行边界无默认）"),
                "timeoutSeconds", new ValueSchema.Opt(new ValueSchema.Num(
                        "任务总期限秒数（缺省 300；超时终止并落 failed(timed_out)）")),
                "outputCapBytes", new ValueSchema.Opt(new ValueSchema.Num(
                        "输出捕获字节上限（缺省 262144；达限停止捕获不终止任务）"))));
        return ToolDefinition.of("job_start", START_DESCRIPTION, schema, (args, context) -> {
            try {
                JobRequest request = new JobRequest(args.readString("command"),
                        Path.of(args.readString("cwd")),
                        Duration.ofSeconds(args.readOptionalLong("timeoutSeconds")
                                .orElse(DEFAULT_TIMEOUT_SECONDS)),
                        args.readOptionalLong("outputCapBytes").orElse(DEFAULT_OUTPUT_CAP_BYTES));
                return ToolExecutionResult.success(JobToolOutput.status(service.start(request)));
            } catch (JobException e) {
                return ToolExecutionResult.error(JobToolOutput.error(e.getMessage()));
            } catch (IllegalArgumentException e) {
                return ToolExecutionResult.error("参数错误（" + e.getMessage() + "）");
            }
        });
    }

    private static ToolDefinition statusTool(JobService service) {
        ValueSchema schema = new ValueSchema.Object("参数", Map.of(
                "jobId", new ValueSchema.Str("任务 id（job_start 返回）"),
                "waitSeconds", new ValueSchema.Opt(new ValueSchema.Num(
                        "等待终局的期限秒数（缺省 0 = 立即返回当前快照）"))));
        return ToolDefinition.of("job_status", STATUS_DESCRIPTION, schema, (args, context) -> {
            try {
                Id<Job> id = Job.newId(args.readString("jobId"));
                long waitMillis = args.readOptionalLong("waitSeconds").orElse(0L) * 1000L;
                return ToolExecutionResult.success(JobToolOutput.status(await(service, id, waitMillis)));
            } catch (JobNotFoundException e) {
                return ToolExecutionResult.error(JobToolOutput.error(e.getMessage()));
            } catch (IllegalArgumentException e) {
                return ToolExecutionResult.error("参数错误（" + e.getMessage() + "）");
            }
        });
    }

    private static ToolDefinition readTool(JobService service, JobsToolConfig config) {
        ValueSchema schema = new ValueSchema.Object("参数", Map.of(
                "jobId", new ValueSchema.Str("任务 id"),
                "fromByte", new ValueSchema.Opt(new ValueSchema.Num("起始字节游标（缺省 0）")),
                "maxBytes", new ValueSchema.Opt(new ValueSchema.Num(
                        "本片字节上限（缺省与上限均为行配置 maxReadBytes）"))));
        return ToolDefinition.of("job_read", READ_DESCRIPTION, schema, (args, context) -> {
            try {
                Id<Job> id = Job.newId(args.readString("jobId"));
                long from = args.readOptionalLong("fromByte").orElse(0L);
                int max = (int) Math.min(args.readOptionalLong("maxBytes")
                        .orElse(config.maxReadBytes()), config.maxReadBytes());
                JobSnapshot snapshot = service.snapshot(id);
                return ToolExecutionResult.success(
                        JobToolOutput.read(snapshot, service.read(id, from, max)));
            } catch (JobException e) {
                return ToolExecutionResult.error(JobToolOutput.error(e.getMessage()));
            } catch (IllegalArgumentException e) {
                return ToolExecutionResult.error("参数错误（" + e.getMessage() + "）");
            }
        });
    }

    private static ToolDefinition cancelTool(JobService service) {
        ValueSchema schema = new ValueSchema.Object("参数", Map.of(
                "jobId", new ValueSchema.Str("任务 id")));
        return ToolDefinition.of("job_cancel", CANCEL_DESCRIPTION, schema, (args, context) -> {
            try {
                boolean cancelled = service.cancel(Job.newId(args.readString("jobId")));
                return ToolExecutionResult.success(cancelled
                        ? "已发起取消收敛（TERM→KILL）"
                        : "任务已终局，未发起取消");
            } catch (JobException e) {
                return ToolExecutionResult.error(JobToolOutput.error(e.getMessage()));
            } catch (IllegalArgumentException e) {
                return ToolExecutionResult.error("参数错误（" + e.getMessage() + "）");
            }
        });
    }

    /** 工具层轮询：期限内等终局（200ms 步进），到期返回当前快照——不在 seam 加等待原语。 */
    private static JobSnapshot await(JobService service, Id<Job> id, long waitMillis) {
        long deadline = System.currentTimeMillis() + waitMillis;
        JobSnapshot snapshot = service.snapshot(id);
        while (!snapshot.state().terminal() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            snapshot = service.snapshot(id);
        }
        return snapshot;
    }
}
