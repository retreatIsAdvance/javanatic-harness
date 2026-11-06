package io.javanatic.harness.delegation.local;

import io.javanatic.harness.kernel.scope.Scope;
import java.util.function.Consumer;

/**
 * 子代继承快照的装配期执法点（停 2 设计 §3）：唯一允许触碰子 scope 的地方
 * （CreateAgentOptions.setup 回调）；LocalDelegationService 永不持有子 scope 引用——
 * ArchUnit 双路背书之一（另一路：扩注探针集成测试）。v1 继承面：工具面与预算经
 * 外层收窄（预算在请求；工具收窄面待停 3 委派工具存在后以 preset 挂载收窄——
 * maxDepth=1 的结构性执法随停 3 落地，本类是它的落点）；沙箱与审批档 v1 恒等继承
 * （恒等也是显式决定，见设计 §3）。
 */
final class DelegationChildSetup implements Consumer<Scope> {

    @Override
    public void accept(Scope childScope) {
        // v1 恒等继承：无收窄动作。结构上唯一经此——扩注能力只应出现在这里，
        // 而这里只做装配期收窄（停 3 起挂 preset 摘除委派工具）。
    }
}
