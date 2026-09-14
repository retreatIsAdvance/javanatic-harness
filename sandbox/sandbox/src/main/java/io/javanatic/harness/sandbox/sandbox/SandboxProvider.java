package io.javanatic.harness.sandbox.sandbox;

import io.javanatic.harness.kernel.scope.ServiceKey;

import java.util.List;

/**
 * 同机进程约束服务（dsh SandboxProvider 形状）。与宿主共享内核与文件系统；
 * 容器/microVM/远程执行是<b>换掉整条执行 seam</b>（如未来的 shell-docker
 * Provider），不挂在本服务后面。
 */
public interface SandboxProvider {

    /** 本服务的服务键。 */
    ServiceKey<SandboxProvider> KEY = new ServiceKey<>("sandbox");

    /**
     * 包装 argv 使其按 policy 受限执行——调用方以返回值<b>替代自身</b> spawn。
     *
     * @param argv   调用方即将 spawn 的确切 argv（程序+参数；shell 形消费者
     *               传 {@code [bash, -c, command]}），不是 shell 串
     * @param policy 本次执行的文件效果策略（须为受限档——透传档是调用方的
     *               显式弃权，不进 provider）
     * @return 包装后 argv + 本后端强制完备度 + 拒绝方言
     * @throws IllegalArgumentException    argv 为空/含 null 或 policy 为透传档
     * @throws SandboxUnavailableException 本宿主无可强制后端（fail-closed，
     *                                     禁止静默透传）
     */
    ConfinedArgv confine(List<String> argv, SandboxPolicy policy);

    /**
     * 查询本宿主同机后端的可用性（verify/preflight 预警用；不抛异常，
     * 不可用即状态）。探针与 {@link #confine} 共用同一份首探缓存。
     *
     * @return 后端可用 / 平台无后端 / 候选探针失败
     */
    BackendStatus backendStatus();
}
