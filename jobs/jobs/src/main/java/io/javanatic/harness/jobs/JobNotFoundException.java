package io.javanatic.harness.jobs;

/** 任务 id 在本会话作用域内不存在——fail loud，不以空结果冒充。 */
public class JobNotFoundException extends JobException {

    /** @param id 不被识别的任务 id */
    public JobNotFoundException(String id) {
        super("job not found: " + id);
    }
}
