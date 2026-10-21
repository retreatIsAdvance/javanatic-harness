package io.javanatic.harness.web.fetch.http;

import java.net.InetAddress;
import java.util.List;

/**
 * 地址闸：对一次解析得到的完整地址组做统一校验（任一不合格即整组拒绝）。
 * 生产实现是 {@link PublicAddressGate}；测试注入允许回环的实现以跑本地夹具。
 */
@FunctionalInterface
interface AddressGate {

    /**
     * @param host 主机名（诊断用）
     * @param addresses 非空地址组
     * @throws io.javanatic.harness.web.WebException 任一地址不合格时
     */
    void verify(String host, List<InetAddress> addresses);
}
