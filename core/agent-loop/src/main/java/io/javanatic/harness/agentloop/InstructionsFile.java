package io.javanatic.harness.agentloop;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * 项目说明文件的装载与指纹（it21）：轮首读 cwd 下的说明文件（行配置 {@code agent-loop
 * .instructionsFile}，缺省 {@code AGENTS.md}），内容以 {@link
 * io.javanatic.harness.session.event.ProjectInstructions} 落账。
 *
 * <p>有界：超过 {@link #MAX_BYTES} 截断并置 truncated 位（截断点回退到最后一个换行，
 * 整行收尾；通篇无换行则保留字节前缀）。sha256 供 loop 做「内容未变不追加」的变化
 * 检测（日志有界）；指纹取装载内容——超限文件的尾部（截断点之后）变化不触发重装。
 */
final class InstructionsFile {

    /** 单次装载的字节上限（文档化默认：64 KiB；超限截断 + truncated 位）。 */
    static final int MAX_BYTES = 64 * 1024;

    private InstructionsFile() {
    }

    /**
     * @throws IOException 文件不存在或不可读（调用方决定静默/WARN）
     */
    static Loaded read(Path file) throws IOException {
        byte[] bytes;
        boolean truncated;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes(MAX_BYTES);
            truncated = in.read() != -1;
        }
        if (truncated) {
            for (int i = bytes.length - 1; i >= 0; i--) {
                if (bytes[i] == '\n') {
                    bytes = Arrays.copyOf(bytes, i + 1);
                    break;
                }
            }
        }
        return new Loaded(new String(bytes, StandardCharsets.UTF_8), truncated);
    }

    /** 内容 SHA-256（十六进制小写）。 */
    static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** 装载结果：内容（截断后）+ 是否截断。 */
    record Loaded(String content, boolean truncated) {
    }
}
