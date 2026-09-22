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
     * 精确替换 {@code oldString}：须唯一匹配（it21）——出现 0 次或 ≥2 次均拒绝，
     * 不静默改第一处；≥2 次时消息含出现次数与行号（多处按非重叠计，如
     * {@code "aa"} 在 {@code "aaa"} 中算 1 处），补足上下文消歧后重试。
     *
     * @return 编辑后的文件全文
     * @throws IOException 读取/写入失败
     * @throws IllegalArgumentException oldString 为空或不存在时；出现多次时（消息含计数与行号）；
     *         文件超过实现配置的读取上限时（fail loud，消息含实际大小与上限）
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

    /**
     * 字面搜索（it21）：在 {@code path} 下递归查找含 {@code pattern} 字面串的行
     * （不做正则/glob——留待独立 seam）。
     *
     * <p><b>有界</b>：实现方按匹配上限停收（{@link SearchResult#truncated()} 承载）、
     * 逐条行文本截断；二进制与超读取上限的文件跳过；不跟随符号链接（目录不进、
     * 文件不读），候选按真实路径校验围栏。
     *
     * @return 匹配（实现定义的稳定坐标系：LocalFs 为相对工作区根、{@code /} 分隔，
     *         按路径与行号排序）+ 是否因匹配上限截断
     * @throws IOException 起始路径不可用（不存在、不是目录）
     * @throws IllegalArgumentException pattern 为空或起始路径越出工作区根时
     */
    SearchResult search(String pattern, Path path) throws IOException;

    /** 一条搜索匹配：相对路径、1 起行号、行文本（超上限时截断）。 */
    record Match(String path, int line, String text) {}

    /** 搜索结果：匹配 + 是否因匹配上限截断（不静默丢数据）。 */
    record SearchResult(List<Match> matches, boolean truncated) {}
}
