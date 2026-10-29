package com.medbox.server.ws;

/**
 * WebSocket 帧类型（见文档 04 第 2 / 3 章）。
 *
 * <p>文档里列了 {@code ALARM / DEVICE_STATUS / REMINDER / AI_STREAM / PONG}，本分支的取舍：
 * <ul>
 *   <li>{@link #AI_STREAM} <b>不实现</b>：文档 04 第 3 章明确"本期不使用"，AI 流式统一走 HTTP SSE（文档 05）；</li>
 *   <li>{@link #REMINDER} <b>只登记枚举、本分支不发</b>：触发逻辑在 {@code feat/plan-reminder}，
 *       payload 形态（{@code medicines[]}）已定，先留位免得后续分支改枚举；</li>
 *   <li>{@link #ALARM} / {@link #DEVICE_STATUS} <b>本分支落地</b>（07 清单要求）；</li>
 *   <li>{@link #RESYNC} 是本分支新增：补推窗口过期时告诉客户端"改走 REST 拉全量"，
 *       否则客户端无法区分"没有新事件"与"事件已被淘汰"（已同步补进文档 04）。</li>
 * </ul>
 */
public enum WsFrameType {

    /** 告警（漏服 / 环境 / 设备离线 / 设备故障）。 */
    ALARM,

    /** 设备在线状态（含传感器子设备）。 */
    DEVICE_STATUS,

    /** 服药提醒（触发逻辑在 {@code feat/plan-reminder}，本分支只登记类型）。 */
    REMINDER,

    /** AI 流式分片：<b>本期不使用</b>，只在文档里占位。 */
    AI_STREAM,

    /** 心跳应答。 */
    PONG,

    /** 补推窗口已过期，请客户端改走 REST 拉取全量。 */
    RESYNC;

    /** 是否允许由业务方通过 {@link WsPushService} 主动推送（内部帧走专用通道）。 */
    public boolean pushable() {
        return this == ALARM || this == DEVICE_STATUS || this == REMINDER;
    }
}
