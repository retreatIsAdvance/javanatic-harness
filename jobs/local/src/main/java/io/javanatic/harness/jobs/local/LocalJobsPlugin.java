package io.javanatic.harness.jobs.local;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agentloop.AgentEvents;
import io.javanatic.harness.jobs.JobService;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import java.nio.file.Path;

/**
 * 本地后台任务插件（id "jobs-local"，it30 停 2）：provide JobService；
 * 经 agent/created 拿 Session 绑定（**首个以 Provider 角色、在轮管线时点之外
 * append 会话事件的模块**——skill/mcp 先例全在轮管线单线程时点，偏离注记见
 * 停 2 设计 §4.A）。任务树根缺省 {@code <tmpdir>/jh-jobs}（B27 homeDir 先例）；
 * scope 关闭 = 逐任务收敛 Cancelled，输出文件保留为遗物。
 */
public final class LocalJobsPlugin implements Plugin {

    @Override
    public String id() {
        return "jobs-local";
    }

    @Override
    public void apply(Scope scope) {
        LocalJobService service = new LocalJobService(
                Path.of(System.getProperty("java.io.tmpdir"), "jh-jobs"));
        scope.provide(JobService.KEY, service);
        scope.onClose(service::close);
        scope.events().on(AgentEvents.CREATED,
                (carrier, agent) -> service.bindSession(((Agent) carrier).session()));
    }
}
