package com.medbox.server.ws;

import java.util.OptionalLong;

/**
 * 事件 ID：对外是 {@code evt-9a1c} 这种短字符串，对内是一个单调递增的 {@code long} 序号。
 *
 * <p>为什么要两层：文档 04 要求 id "单调递增 / 雪花、用于断线补推"，而补推本质是"给我
 * {@code lastEventId} 之后的事件" —— 那是**数值比较**。字符串形态留给人看与传参，序号留给
 * 服务端比较，两边用同一套 {@code evt-} + 十六进制互相转换（与文档示例 {@code evt-9a1c} 同形）。
 *
 * <p>单实例部署下 {@link WsPushService} 用一个 {@code AtomicLong} 发号即可保证单调；
 * 将来多实例再换成雪花或数据库序列，只要保持"序号可比较"，补推逻辑不用改。
 */
public final class WsEventId {

    /** 事件 ID 前缀，与文档 04 示例一致。 */
    public static final String PREFIX = "evt-";

    /** 十六进制位数上限（long 最多 16 位）。 */
    private static final int MAX_HEX_LENGTH = 16;

    /** 未指定 lastEventId 时的起始序号（即"从头开始"，补推时会跳过它）。 */
    public static final long NO_SEQ = 0L;

    private WsEventId() {
    }

    /** 序号 → 事件 ID。 */
    public static String format(long seq) {
        return PREFIX + Long.toHexString(seq);
    }

    /**
     * 事件 ID → 序号；非法（空 / 无前缀 / 非十六进制 / 超长）返回 {@link OptionalLong#empty()}，
     * 由调用方决定是"从头补"还是拒绝，不抛异常。
     */
    public static OptionalLong parse(String id) {
        if (id == null) {
            return OptionalLong.empty();
        }
        String value = id.trim();
        if (!value.startsWith(PREFIX)) {
            return OptionalLong.empty();
        }
        String hex = value.substring(PREFIX.length());
        if (hex.isEmpty() || hex.length() > MAX_HEX_LENGTH) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(hex, 16));
        } catch (NumberFormatException ex) {
            return OptionalLong.empty();
        }
    }
}
