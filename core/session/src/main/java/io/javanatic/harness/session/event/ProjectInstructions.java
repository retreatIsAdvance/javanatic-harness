package io.javanatic.harness.session.event;

/**
 * 项目说明事实(log-only,ignorable 遥测性):轮首装载的工作区说明文件快照,
 * 系统提示词组装读它渲染说明段——同日志必同提示词(R1)。
 *
 * <p>进入提示词即「模型可见」,但不改变任何权限位:装载只读文件、不触碰
 * 工具注册/沙箱模式/审批模式(it21 验收)。内容与上一条完全相同时 loop 不追加,
 * 日志不被重复内容撑爆;读取方取最新一条渲染。
 *
 * @param path 说明文件路径(组合配置 agent-loop.cwd 与 instructionsFile 派生)
 * @param sha256 内容 SHA-256(十六进制小写),变化检测的依据
 * @param truncated 内容是否因装载上限被截断
 * @param content 说明文件内容(截断后;截断时以换行边界收尾)
 */
public record ProjectInstructions(long time, String path, String sha256, boolean truncated,
                                  String content) implements SessionEvent {

    @Override public String type() { return "project/instructions"; }

    @Override public boolean ignorable() { return true; }
}
