package com.medbox.server.dto.plan;

import java.util.List;

/**
 * 未来待服时间线的一项：某次服药的本地时间、换算后的 UTC，以及所需药品清单。
 *
 * <p><b>两个时间都是字符串、由服务端按口径格式化</b>，不依赖 Jackson 对 {@code OffsetDateTime}
 * 的默认序列化（Boot 4 的 Jackson 3 与旧版不一致），保证 API 契约稳定：
 * <ul>
 *   <li>{@code scheduledTimeLocal}：设备本地墙钟（无偏移，形如 {@code 2026-10-21T08:00}）；</li>
 *   <li>{@code scheduledTimeUtc}：换算成 UTC 后的带偏移时间（ISO，形如
 *       {@code 2026-10-21T00:00:00Z} 或 {@code 2026-10-21T01:00:00+08:00}），
 *       供客户端统一用 UTC 做提醒调度。</li>
 * </ul>
 *
 * <p><b>时区换算口径（文档 01 第 1 章 / 02 第 6 章）</b>："今天日期 + 该本地时间"按老人主控设备的
 * {@code device.timezone} 得到墙钟时间，再换算成 UTC。
 *
 * @param scheduledTimeUtc   换算后的 UTC 时间（ISO 带偏移）
 * @param scheduledTimeLocal 设备本地墙钟时间（无偏移）
 * @param items              本次服药所需药品
 */
public record NextDoseResponse(
    String scheduledTimeUtc,
    String scheduledTimeLocal,
    List<NextDoseItemResponse> items
) {
}
