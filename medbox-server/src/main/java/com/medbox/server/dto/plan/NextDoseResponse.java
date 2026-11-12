package com.medbox.server.dto.plan;

/**
 * 未来待服时间线的一项：某号格的服药本地时间、换算后的 UTC 时刻。
 *
 * <p><b>两个时间都是字符串、由服务端按口径格式化</b>，不依赖 Jackson 对 {@code OffsetDateTime}
 * 的默认序列化（Boot 4 的 Jackson 3 与旧版不一致），保证 API 契约稳定：
 * <ul>
 *   <li>{@code scheduledTimeLocal}：设备本地墙钟（无偏移，形如 {@code 2026-10-21T08:00}）；</li>
 *   <li>{@code scheduledTimeUtc}：换算成 UTC 后的带偏移时间（ISO，形如
 *       {@code 2026-10-21T00:00:00Z}），供客户端统一用 UTC 做提醒调度。</li>
 * </ul>
 *
 * <p><b>时区换算口径</b>："今天日期 + 该格开格时间"按老人主控设备的
 * {@code device.timezone} 得到墙钟时间，再换算成 UTC。每条含 {@code cellNo}，不含药品信息。
 *
 * @param cellNo             药格格号 1 / 2 / 3
 * @param scheduledTimeUtc   换算后的 UTC 时间（ISO 带偏移）
 * @param scheduledTimeLocal 设备本地墙钟时间（无偏移）
 */
public record NextDoseResponse(
    Integer cellNo,
    String scheduledTimeUtc,
    String scheduledTimeLocal
) {
}
