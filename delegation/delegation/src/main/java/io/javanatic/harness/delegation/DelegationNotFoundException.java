package io.javanatic.harness.delegation;

/** 委派 id 不存在——fail loud，不以空结果冒充。 */
public class DelegationNotFoundException extends DelegationException {

    /** @param id 不被识别的委派 id */
    public DelegationNotFoundException(String id) {
        super("delegation not found: " + id);
    }
}
