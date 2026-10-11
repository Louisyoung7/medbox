package com.medbox.server.ws;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 断线补推用的事件缓冲：**每个主题**只保留最近 {@code maxEvents} 条 / {@code windowMinutes} 分钟的事件
 * （文档 04 第 1 章：最近 200 条 / 10 分钟）。
 *
 * <p><b>为什么放内存</b>：骨架期是单实例部署，内存缓冲零依赖、零延迟；代价是**进程重启即丢、多实例之间
 * 不共享** —— 届时可换成 Redis / 表，但 {@link WsPushService} 的签名与补推语义不变（调用方不用改）。
 * 这是有意识的债，不是遗漏。
 *
 * <p><b>为什么要额外记 {@code lastIssuedSeq}</b>：只看缓冲里剩下的事件无法区分"客户端已追上最新"
 * 与"它要的事件早被淘汰了"。记下每个主题发过的最大序号，就能判断"你落后了但事件已不在窗口内"，
 * 从而回 {@code RESYNC} 让客户端改走 REST 全量，而不是静默什么都不推。
 */
public class WsEventBuffer {

    /** 补推结果。 */
    public record ReplayResult(List<WsFrame> events, boolean windowExpired, String latestEventId) {

        /** 无需补推的常量（首次连接 / 客户端已追上）。 */
        public static final ReplayResult EMPTY = new ReplayResult(List.of(), false, null);
    }

    /** 缓冲条目：对外是帧，对内还要序号做比较。 */
    private record Entry(long seq, WsFrame frame) {
    }

    private final int maxEvents;
    private final long windowMillis;
    private final Clock clock;

    private final ConcurrentHashMap<String, Deque<Entry>> byTopic = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lastIssuedSeq = new ConcurrentHashMap<>();

    public WsEventBuffer(WsProperties props) {
        this(props, Clock.systemUTC());
    }

    public WsEventBuffer(WsProperties props, Clock clock) {
        this.maxEvents = props.getReplay().getMaxEvents();
        this.windowMillis = props.getReplay().getWindowMinutes() * 60L * 1000L;
        this.clock = clock;
    }

    /** 写入一条事件并顺带做淘汰（不额外起定时任务：写入频率就是清理频率）。 */
    public void append(String topic, long seq, WsFrame frame) {
        Deque<Entry> deque = byTopic.computeIfAbsent(topic, key -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new Entry(seq, frame));
            evictLocked(deque);
        }
        lastIssuedSeq.computeIfAbsent(topic, key -> new AtomicLong()).set(seq);
    }

    /**
     * 取 {@code lastSeq} 之后的增量事件。
     *
     * @param lastSeq 客户端带来的 {@code lastEventId} 序号；{@link WsEventId#NO_SEQ} 表示不补推
     */
    public ReplayResult replaySince(String topic, long lastSeq) {
        if (lastSeq <= WsEventId.NO_SEQ) {
            return ReplayResult.EMPTY;
        }
        List<WsFrame> events = new ArrayList<>();
        long latest = lastSeq;
        boolean gap = false;
        Deque<Entry> deque = byTopic.get(topic);
        if (deque != null) {
            synchronized (deque) {
                evictLocked(deque);
                for (Entry entry : deque) {
                    if (entry.seq() > lastSeq) {
                        events.add(entry.frame());
                    }
                    if (entry.seq() > latest) {
                        latest = entry.seq();
                    }
                }
                Entry oldest = deque.peekFirst();
                // 最老的一条都比 lastSeq+1 大 → 中间的事件已被淘汰，出现断档
                gap = oldest != null && oldest.seq() > lastSeq + 1;
            }
        }
        long issued = lastIssuedSeq.getOrDefault(topic, new AtomicLong(lastSeq)).get();
        boolean windowExpired = gap || (events.isEmpty() && issued > lastSeq);
        String latestId = latest > WsEventId.NO_SEQ ? WsEventId.format(latest) : null;
        return new ReplayResult(List.copyOf(events), windowExpired, latestId);
    }

    /** 该主题当前缓冲的条数（测试与排障用）。 */
    public int size(String topic) {
        Deque<Entry> deque = byTopic.get(topic);
        if (deque == null) {
            return 0;
        }
        synchronized (deque) {
            return deque.size();
        }
    }

    /** 有缓冲的主题数。 */
    public int topicCount() {
        return byTopic.size();
    }

    /** 清空缓冲（仅测试用）。 */
    public void clear() {
        byTopic.clear();
        lastIssuedSeq.clear();
    }

    /** 淘汰：既按条数上限，也按时间窗口。调用方需持有 deque 的锁。 */
    private void evictLocked(Deque<Entry> deque) {
        long cutoff = clock.millis() - windowMillis;
        while (deque.size() > maxEvents
                || (!deque.isEmpty() && deque.peekFirst().frame().ts() < cutoff)) {
            deque.pollFirst();
        }
    }
}
