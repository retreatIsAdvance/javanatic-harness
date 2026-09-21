package io.javanatic.harness.fs;

import io.javanatic.harness.kernel.scope.ServiceKey;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** 文件系统能力：阻塞语义（虚拟线程下安全），实现方保证原子可见性约定。 */
public interface FsService {

    /** 本服务的服务键。 */
    ServiceKey<FsService> KEY = new ServiceKey<>("fs");

    /** 读取截断标记（it20）：{@link #read} 返回值被截断时以它结尾，与 shell-tool 同文本。 */
    String READ_TRUNCATED_MARKER = " (output truncated)";

    /**
     * 有界读取：实现方按配置的字节上限截断（it20 契约）——截断是内容事实，不是错误，
     * 返回值以 {@link #READ_TRUNCATED_MARKER} 结尾；未截断时返回全文。
     *
     * @throws IOException 读取失败（含不存在）
     */
    String read(Path path) throws IOException;

    /**
     * @throws IOException 写入失败
     */
    void write(Path path, String content) throws IOException;

    /**
     * 精确替换第一处 {@code oldString}。
     * @throws IOException 读取/写入失败
     * @throws IllegalArgumentException oldString 不存在时；文件超过实现配置的读取上限时（fail loud，消息含实际大小与上限）
     */
    String edit(Path path, String oldString, String newString) throws IOException;

    /**
     * @throws IOException 删除失败（含不存在）
     */
    void delete(Path path) throws IOException;

    /**
     * 列举目录条目：按名称排序；实现方按配置的条目上限截断（it20 契约），
     * 截断以 {@link Listing#truncated()} 承载而非静默丢弃。
     *
     * @throws IOException 列举失败（含不是目录）
     */
    Listing list(Path path) throws IOException;

    /** 目录条目。 */
    record DirEntry(String name, boolean directory) {}

    /** 列举结果：按名排序的条目 + 是否因条目上限截断。 */
    record Listing(List<DirEntry> entries, boolean truncated) {}
}
