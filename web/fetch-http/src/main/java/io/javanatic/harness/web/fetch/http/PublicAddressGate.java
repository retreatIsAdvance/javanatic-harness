package io.javanatic.harness.web.fetch.http;

import java.net.InetAddress;
import java.util.List;

import io.javanatic.harness.web.WebErrorCode;
import io.javanatic.harness.web.WebException;

/**
 * 公网地址闸：字面 IP 与 DNS 解析结果走同一份判定（T2 条件 ③）。
 *
 * <p>整组语义：调用方一次解析拿到全部答案，本闸逐一判定，任一非公网即整组拒绝
 * （防多答案里夹私网）。IPv4 拒私网/回环/链路本地/CGNAT/文档/基准/组播/保留/广播；
 * IPv6 只接受 {@code 2000::/3}，并显式剔除 Teredo、6to4、文档段、NAT64 标准/本地前缀；
 * IPv4-mapped（JDK 归一为 Inet4Address）按内嵌 IPv4 同闸；{@code ::/96} 兼容段整段拒。
 *
 * <p><b>残余（B17）</b>：RFC 7050 自定义 NAT64 前缀发现不实现——只封标准前缀
 * {@code 64:ff9b::/96} 与本地前缀 {@code 64:ff9b:1::/48}。自定义前缀在结构上仍是
 * {@code 2000::/3} 内的全球单播地址，本闸无法从地址本身识别其转换语义。
 */
final class PublicAddressGate implements AddressGate {

    /** 无状态单例（生产装配用）。 */
    static final PublicAddressGate INSTANCE = new PublicAddressGate();

    @Override
    public void verify(String host, List<InetAddress> addresses) {
        if (addresses.isEmpty()) {
            throw blocked(host, "empty address set");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw blocked(host, address.getHostAddress());
            }
        }
    }

    /**
     * @param address 待判地址
     * @return 是否可作为公网抓取目标
     */
    static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isPublicIpv4(bytes);
        }
        return isPublicIpv6(bytes);
    }

    private static boolean isPublicIpv4(byte[] b) {
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        int third = b[2] & 0xFF;
        // 0/8 本网络、10/8 私网、127/8 回环
        if (first == 0 || first == 10 || first == 127) {
            return false;
        }
        // 100.64/10 CGNAT
        if (first == 100 && second >= 64 && second <= 127) {
            return false;
        }
        // 169.254/16 链路本地
        if (first == 169 && second == 254) {
            return false;
        }
        // 172.16/12 私网
        if (first == 172 && second >= 16 && second <= 31) {
            return false;
        }
        // 192.168/16 私网
        if (first == 192 && second == 168) {
            return false;
        }
        // 192.0.0/24 IETF 协议分配、192.0.2/24 文档、192.88.99/24 6to4 中继
        if (first == 192 && second == 0 && (third == 0 || third == 2)) {
            return false;
        }
        if (first == 192 && second == 88 && third == 99) {
            return false;
        }
        // 198.18/15 基准、198.51.100/24 文档、203.0.113/24 文档
        if (first == 198 && (second == 18 || second == 19)) {
            return false;
        }
        if (first == 198 && second == 51 && third == 100) {
            return false;
        }
        if (first == 203 && second == 0 && third == 113) {
            return false;
        }
        // 224/4 组播、240/4 保留（含 255.255.255.255 广播）
        return first < 224;
    }

    private static boolean isPublicIpv6(byte[] b) {
        // ::ffff:0:0/96（IPv4-mapped）：按内嵌 IPv4 过同一闸。JDK 通常已归一为 Inet4，
        // 这里仍显式处理直接由 16 字节构造的形态，避免漏判。
        if (allZero(b, 0, 10) && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
            return isPublicIpv4(new byte[] {b[12], b[13], b[14], b[15]});
        }
        // ::/96（未指定、回环、IPv4-compatible）整段拒
        if (allZero(b, 0, 12)) {
            return false;
        }
        // 64:ff9b::/96 与 64:ff9b:1::/48（NAT64 标准/本地前缀）
        if ((b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64
            && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) {
            if (allZero(b, 4, 12) || (b[4] == 0 && b[5] == 1)) {
                return false;
            }
        }
        // 只接受 2000::/3 全球单播
        if ((b[0] & 0xE0) != 0x20) {
            return false;
        }
        // 2001:0000::/23（Teredo、基准、协议分配）
        if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01 && (b[2] & 0xFF) <= 0x01) {
            return false;
        }
        // 2001:db8::/32 文档
        if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01
            && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8) {
            return false;
        }
        // 2002::/16 6to4
        if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) {
            return false;
        }
        // 3fff::/20 文档（RFC 9637）
        return !((b[0] & 0xFF) == 0x3F && (b[1] & 0xFF) == 0xFF && (b[2] & 0xF0) == 0x00);
    }

    private static boolean allZero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** 诊断信息不回显 URL 其他部分，只给主机与命中地址。 */
    private static WebException blocked(String host, String address) {
        return new WebException(WebErrorCode.WEB_BLOCKED_ADDRESS,
            "blocked non-public address for host '" + host + "': " + address);
    }
}
