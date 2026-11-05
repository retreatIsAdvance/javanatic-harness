package io.javanatic.harness.jobs.local;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agentloop.AgentEvents;
import io.javanatic.harness.jobs.JobService;
import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import java.nio.file.Path;

/**
 * 本地后台任务插件（id "jobs-local"，it30 停 2）：provide JobService；
 * 经 agent/created 拿 Session 绑定（**首个以 Provider 角色、在轮管线时点之外
 * append 会话事件的模块**——skill/mcp 先例全在轮管线单线程时点，偏离注记见
 * 停 2 设计 §4.A）。任务树根行配置 {@code dataDir}（B30），缺省
 * {@code ${home}/.harness/jobs/}——重启确定性存活（Lost 遗物只读的契约承诺
 * 不架在 OS tmp 清理策略上，停 2 复跑改判）；测试经构造器注入临时目录。
 * scope 关闭 = 逐任务收敛 Cancelled，输出文件保留为遗物。
 */
public final class LocalJobsPlugin implements Plugin {

    /** 任务树根缺省（与 .harness/sessions 同族的确定位）。 */
    static final Path DEFAULT_DATA_DIR = Path.of(System.getProperty("user.home"), ".harness", "jobs");

    @Override
    public String id() {
        return "jobs-local";
    }

    @Override
    public void apply(Scope scope) {
        Path dataDir = Path.of(scope.resolve(ConfigService.KEY)
                .map(config -> ConfigValues.stringValue(config.configFor(id()), id(),
                        "dataDir", DEFAULT_DATA_DIR.toString()))
                .orElse(DEFAULT_DATA_DIR.toString()));
        LocalJobService service = new LocalJobService(dataDir);
        scope.provide(JobService.KEY, service);
        scope.onClose(service::close);
        scope.events().on(AgentEvents.CREATED,
                (carrier, agent) -> service.bindSession(((Agent) carrier).session()));
    }
}
