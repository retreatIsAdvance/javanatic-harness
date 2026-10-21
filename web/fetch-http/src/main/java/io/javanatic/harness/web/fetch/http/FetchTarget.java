package io.javanatic.harness.web.fetch.http;

import java.net.URI;

/**
 * 规范化后的抓取目标：已经过 URL 策略（https、无 userinfo、长度、IDN），
 * 尚未做地址闸与连接。{@code host} 是 ASCII/punycode 形式（IPv6 无括号）。
 *
 * @param base 以本目标为基准做重定向解析的 URI（无 fragment）
 * @param host ASCII 主机名或 IP 字面量（IPv6 无括号）
 * @param port 端口（1–65535）
 * @param hostHeader Host 头值（IPv6 带括号，非 443 带端口）
 * @param requestTarget 请求行目标（path + query）
 * @param ipLiteral 是否为 IP 字面量（字面量与 DNS 结果同过地址闸）
 */
record FetchTarget(URI base, String host, int port, String hostHeader, String requestTarget,
                   boolean ipLiteral) {
}
