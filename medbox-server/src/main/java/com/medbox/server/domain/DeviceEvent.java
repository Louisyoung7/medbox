package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.medbox.server.domain.enums.DeviceEventType;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 设备事件流水，对应 {@code device_event} 表（见文档 06 的 2.16，由 V6 建）。
 *
 * <p><b>为什么不记进 {@code mqtt_message}</b>：那张表是上行消息**去重账本**，
 * 只有 {@code msg_id} 与 topic、没有事件类型语义，且 7 天就过期清理。
 * 本表是面向监护人长期可查的业务数据，两者作用域与生命周期都不同。
 *
 * <p><b>子设备记自己的 {@code deviceId}</b>，不折算成主控：详情页按主控查流水时
 * 能看到具体是哪颗传感器出了事；归属老人仍从 {@code device.elder_id} 取
 * （子设备的 {@code elder_id} 与父设备一致，见 2.3）。
 *
 * <p><b>本表无业务号</b>：事件只在"某台设备的流水"这个作用域内被引用，
 * 列表接口返回自增主键即可，不额外造 {@code event_id} 业务列。
 */
@Data
@TableName("device_event")
public class DeviceEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 设备业务编号（{@code device.device_id}）；子设备记自己的编号。 */
    private String deviceId;

    /** 事件类型（见 {@link DeviceEventType}）。 */
    private DeviceEventType eventType;

    /** 补充描述（如故障原因），可为空。 */
    private String detail;

    /** 事件发生时间（UTC）。 */
    private OffsetDateTime createdAt;
}
