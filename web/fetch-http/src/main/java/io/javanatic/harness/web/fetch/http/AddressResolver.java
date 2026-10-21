package io.javanatic.harness.web.fetch.http;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 主机名 → 地址组（一次调用取整组；测试注入用，生产为系统解析）。
 * 实现方不得在此做公网校验——校验由 {@link AddressGate} 统一执行。
 */
@FunctionalInterface
interface AddressResolver {

    /**
     * @param host ASCII 主机名
     * @return 非空地址组（顺序保留）
     * @throws UnknownHostException 解析失败
     */
    InetAddress[] resolve(String host) throws UnknownHostException;

    /** @return 系统解析器 */
    static AddressResolver system() {
        return InetAddress::getAllByName;
    }
}
