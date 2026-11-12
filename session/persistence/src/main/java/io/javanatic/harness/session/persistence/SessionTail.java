package io.javanatic.harness.session.persistence;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionHeader;
import io.javanatic.harness.session.event.SessionEvent;

import java.io.IOException;
import java.util.List;

/**
 * 会话日志只读流式跟随读面(Definition)——观察进行中会话的<b>唯一路径</b>:
 * {@link SessionPersistence#load} 对外来写者先探写者锁(占用即拒)再修撕裂尾
 * (修复是写操作),而进行中会话按定义被活跃写者占用,load 必拒。本 seam 与
 * load 的每一步正相反,四契约:
 *
 * <ol>
 * <li><b>无锁</b>——不探针、不获取写者锁;</li>
 * <li><b>不修复撕裂尾</b>——修复是写操作,并发读者只容忍(撕裂尾归 load 的事务域管);</li>
 * <li><b>完整行纪律</b>——末行不完整＝「尚不可用」,下轮再读,不得上抛 IllegalStateException;</li>
 * <li><b>顺序消费、seq 单调</b>——按追加序逐行交付,事件 seq 连续不减(跳号 fail loud,load 同款)。</li>
 * </ol>
 *
 * <p>交付物 = 解码后的 {@link SessionEvent}(load 同款解码:未知 ignorable →
 * {@code ExtensionEvent(time+type)};未知非 ignorable → fail loud)。W2 TUI 与
 * W3 server 共同消费本 seam,三站不各自手搓解析。
 */
public interface SessionTail {

    /** 本服务的服务键。 */
    ServiceKey<SessionTail> KEY = new ServiceKey<>("session-tail");

    /**
     * 打开只读跟随读面(四契约)。open 只读 header 判定存在性,不建目录、不创建锁文件。
     *
     * @param id 会话 id
     * @return 跟随读面游标(新打开的位置从头;close 丢弃游标,重开即全量重放)
     * @throws java.util.NoSuchElementException 会话不在盘上(header.json 缺失,load 同款)
     */
    Follower open(Id<Session> id) throws IOException;

    /** 跟随读面游标:位置推进式只读,零写、零锁、零修复。 */
    interface Follower extends AutoCloseable {

        /** 会话头(open 时读一次;header 由 backfill 一次写成、之后不改)。 */
        SessionHeader header();

        /**
         * 下一批新事件:仅 '\n' 终止的完整行;无新内容返回空列表;
         * 末行不完整＝尚不可用(不交付、不抛,下轮再读)。空行跳过(load 同款,
         * 不占 seq)。交付事件的 seq 从 0 起严格连续(跳号 fail loud)。
         *
         * @return 自上次交付位置以来的全部新事件(可为空)
         * @throws IllegalStateException 日志破损(seq 跳号或不可忽略的未知类型)
         */
        List<SessionEvent> nextEvents() throws IOException;

        /** 丢弃游标(无持久资源;AutoCloseable 为 W3 长驻消费面预留的对称形状)。 */
        @Override
        void close();
    }
}
