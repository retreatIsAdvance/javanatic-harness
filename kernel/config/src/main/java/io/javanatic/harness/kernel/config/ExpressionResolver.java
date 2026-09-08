package io.javanatic.harness.kernel.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受限表达式求值（07 §3）：只插值与比较，不执行代码。变量源白名单
 * {@code env:VAR} / {@code props:KEY} / {@code cwd} / {@code home}；运算仅
 * {@code :-}（默认值）与 {@code == / !=}（对 null 或单引号字面量）。
 * 白名单外的源、无法解析的语法一律 fail loud。
 *
 * <p>求值时机由调用方（AppBoot）保证：行合并完成后、加载前——组合结果
 * 在任何时刻都是完整的，dump 出什么就加载什么。
 */
public final class ExpressionResolver {

    /** ${source[:name][:-default]}；source 白名单外的匹配即拒绝。 */
    private static final Pattern INTERP = Pattern.compile("\\$\\{([^}]+)}");

    /** 比较式：左操作数（插值后可为 null） op 右操作数（null 或 'literal'）。 */
    private static final Pattern COMPARISON = Pattern.compile("^(.*?)\\s*(==|!=)\\s*(null|'[^']*')$");

    /** 变量源：返回变量值；cwd/home 是无名源。 */
    @FunctionalInterface
    public interface Variables {

        /**
         * @param source 变量源（env/props/cwd/home）
         * @param name   变量名（cwd/home 为 null）
         * @return 值；未设置为 empty
         */
        Optional<String> variable(String source, String name);
    }

    private final Variables variables;

    /** @param variables 变量源 */
    public ExpressionResolver(Variables variables) {
        this.variables = Objects.requireNonNull(variables, "variables");
    }

    /** 标准变量源：环境变量 + system properties + cwd/home。 */
    public static ExpressionResolver standard() {
        return new ExpressionResolver((source, name) -> switch (source) {
            case "env" -> Optional.ofNullable(name == null ? null : System.getenv(name));
            case "props" -> Optional.ofNullable(name == null ? null : System.getProperty(name));
            case "cwd" -> Optional.of(System.getProperty("user.dir"));
            case "home" -> Optional.of(System.getProperty("user.home"));
            default -> null;
        });
    }

    /**
     * 插值一个 config 值。整体恰为一个 {@code ${…}} 且变量未设、无默认时返回
     * null（null 语义供比较式使用）；嵌入更大字符串时未设无默认取空串。
     *
     * @param text 原始值（无表达式则原样返回）
     * @return 插值结果
     * @throws IllegalStateException 源不在白名单或语法无法解析时
     */
    public String interpolate(String text) {
        Objects.requireNonNull(text, "text");
        Matcher matcher = INTERP.matcher(text);
        StringBuilder out = new StringBuilder();
        boolean wholeSingle = text.matches("^\\$\\{[^}]+}$");
        String singleValue = null;
        int singleHits = 0;
        while (matcher.find()) {
            String resolved = resolveVariable(matcher.group(1), text);
            singleHits++;
            singleValue = resolved;
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolved == null ? "" : resolved));
        }
        matcher.appendTail(out);
        if (wholeSingle && singleHits == 1) {
            return singleValue;
        }
        return out.toString();
    }

    /** config Map 整体插值（值保持原类型，字符串值才求值）。 */
    public Map<String, Object> interpolate(Map<String, Object> config) {
        Map<String, Object> resolved = new HashMap<>();
        config.forEach((key, value) -> resolved.put(key,
            value instanceof String text ? interpolate(text) : value));
        return resolved;
    }

    /**
     * 求值布尔表达式：{@code <operand> == null} / {@code != null} /
     * {@code == 'lit'} / {@code != 'lit'}；裸操作数为真当且仅当非 null 且非空。
     *
     * @param expression 表达式（如 "${env:KEY} != null"）
     * @throws IllegalStateException 语法无法解析时
     */
    public boolean evaluate(String expression) {
        Objects.requireNonNull(expression, "expression");
        String trimmed = expression.trim();
        rejectUnsupportedOperators(trimmed);
        Matcher comparison = COMPARISON.matcher(trimmed);
        if (comparison.matches()) {
            String left = interpolate(comparison.group(1).trim());
            String operator = comparison.group(2);
            String rightToken = comparison.group(3);
            String right = "null".equals(rightToken) ? null : rightToken.substring(1, rightToken.length() - 1);
            return "==".equals(operator) ? Objects.equals(left, right) : !Objects.equals(left, right);
        }
        String operand = interpolate(trimmed);
        return operand != null && !operand.isEmpty();
    }

    /** 运算白名单外(<、>、单 =、<=、>=)出现即 fail loud——静默当裸操作数是错配吞掉。 */
    private static void rejectUnsupportedOperators(String expression) {
        if (expression.contains("<") || expression.contains(">")
            || expression.contains(" = ") || expression.endsWith(" =")
            || expression.startsWith("= ")) {
            throw new IllegalStateException("expression operator not allowed: " + expression);
        }
    }

    private String resolveVariable(String inner, String whole) {
        String body = inner;
        String fallback = null;
        int defaultAt = body.indexOf(":-");
        if (defaultAt >= 0) {
            fallback = body.substring(defaultAt + 2);
            body = body.substring(0, defaultAt);
        }
        String source;
        String name = null;
        int colon = body.indexOf(':');
        if (colon < 0) {
            source = body;
        } else {
            source = body.substring(0, colon);
            name = body.substring(colon + 1);
            if (name.isEmpty()) {
                throw new IllegalStateException("expression variable name empty in " + whole);
            }
        }
        Optional<String> value = variables.variable(source, name);
        if (value == null) {
            throw new IllegalStateException("expression source not allowed: '" + source + "' in " + whole);
        }
        if (value.isPresent()) {
            return value.get();
        }
        if (fallback != null) {
            return fallback.contains("${") ? interpolate(fallback) : fallback;
        }
        return null;
    }
}
