package io.javanatic.harness.fs;

import io.javanatic.harness.kernel.scope.ServiceKey;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** 文件系统能力：阻塞语义（虚拟线程下安全），实现方保证原子可见性约定。 */
public interface FsService {

    /** 本服务的服务键。 */
    ServiceKey<FsService> KEY = new ServiceKey<>("fs");

    /**
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
     * @throws IllegalArgumentException oldString 不存在时
     */
    String edit(Path path, String oldString, String newString) throws IOException;

    /**
     * @throws IOException 删除失败（含不存在）
     */
    void delete(Path path) throws IOException;

    /**
     * @throws IOException 列举失败（含不是目录）
     */
    List<DirEntry> list(Path path) throws IOException;

    /** 目录条目。 */
    record DirEntry(String name, boolean directory) {}
}
