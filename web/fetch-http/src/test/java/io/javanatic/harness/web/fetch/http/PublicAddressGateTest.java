package io.javanatic.harness.web.fetch.http;

import java.net.InetAddress;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.javanatic.harness.web.WebErrorCode;
import io.javanatic.harness.web.WebException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 地址闸逐网段表：IPv4/IPv6/映射地址 + 整组拒绝 + 字面 IP 同闸。 */
class PublicAddressGateTest {

    private static final PublicAddressGate GATE = PublicAddressGate.INSTANCE;

    @ParameterizedTest(name = "IPv4 {0} 非公网")
    @ValueSource(strings = {
        "0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.169.254",
        "100.64.0.1", "100.127.255.254", "172.16.0.1", "172.31.255.254", "192.168.1.1",
        "192.0.0.9", "192.0.2.1", "192.88.99.1", "198.18.0.1", "198.19.255.254",
        "198.51.100.1", "203.0.113.1", "224.0.0.1", "239.255.255.255",
        "240.0.0.1", "255.255.255.255"
    })
    void nonPublicIpv4IsRejected(String literal) {
        assertThat(PublicAddressGate.isPublic(address(literal))).isFalse();
    }

    @ParameterizedTest(name = "IPv4 {0} 公网")
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "93.184.216.34", "203.0.114.1", "198.51.99.1"})
    void publicIpv4IsAccepted(String literal) {
        assertThat(PublicAddressGate.isPublic(address(literal))).isTrue();
    }

    @ParameterizedTest(name = "IPv6 {0} 非公网")
    @ValueSource(strings = {
        "::", "::1", "fe80::1", "fc00::1", "fd00::1", "ff02::1",
        "2001:db8::1", "2002:808:808::1", "2001::1", "2001:2::1", "3fff::1",
        "64:ff9b::808:808", "64:ff9b:1::808:808", "::127.0.0.1"
    })
    void nonPublicIpv6IsRejected(String literal) {
        assertThat(PublicAddressGate.isPublic(address(literal))).isFalse();
    }

    @ParameterizedTest(name = "IPv6 {0} 公网")
    @ValueSource(strings = {
        "2001:4860:4860::8888", "2606:4700:4700::1111", "2a00:1450:4001:80f::200e", "3ff0::1"
    })
    void publicIpv6IsAccepted(String literal) {
        assertThat(PublicAddressGate.isPublic(address(literal))).isTrue();
    }

    @Test
    void ipv4MappedIsClassifiedByEmbeddedIpv4() {
        assertThat(PublicAddressGate.isPublic(address("::ffff:8.8.8.8"))).isTrue();
        assertThat(PublicAddressGate.isPublic(address("::ffff:127.0.0.1"))).isFalse();
        assertThat(PublicAddressGate.isPublic(address("::ffff:10.0.0.1"))).isFalse();
    }

    @Test
    void anyNonPublicAnswerRejectsWholeSet() {
        List<InetAddress> mixed = List.of(address("8.8.8.8"), address("10.0.0.1"));
        assertThatThrownBy(() -> GATE.verify("mixed.test", mixed))
            .isInstanceOf(WebException.class)
            .satisfies(error -> assertThat(((WebException) error).code())
                .isEqualTo(WebErrorCode.WEB_BLOCKED_ADDRESS))
            .hasMessageContaining("mixed.test")
            .hasMessageContaining("10.0.0.1");
    }

    @Test
    void allPublicAnswersPassAndEmptySetFailsLoud() {
        assertThatCode(() -> GATE.verify("public.test",
            List.of(address("8.8.8.8"), address("1.1.1.1")))).doesNotThrowAnyException();
        assertThatThrownBy(() -> GATE.verify("empty.test", List.of()))
            .isInstanceOf(WebException.class)
            .satisfies(error -> assertThat(((WebException) error).code())
                .isEqualTo(WebErrorCode.WEB_BLOCKED_ADDRESS));
    }

    @Test
    void literalIpGoesThroughTheSameGate() {
        assertThatThrownBy(() -> GATE.verify("127.0.0.1", List.of(address("127.0.0.1"))))
            .isInstanceOf(WebException.class)
            .satisfies(error -> assertThat(((WebException) error).code())
                .isEqualTo(WebErrorCode.WEB_BLOCKED_ADDRESS));
        assertThatThrownBy(() -> GATE.verify("::1", List.of(address("::1"))))
            .isInstanceOf(WebException.class)
            .satisfies(error -> assertThat(((WebException) error).code())
                .isEqualTo(WebErrorCode.WEB_BLOCKED_ADDRESS));
    }

    private static InetAddress address(String literal) {
        return InetAddress.ofLiteral(literal);
    }
}
