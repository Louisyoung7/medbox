package com.medbox.server.dto.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /devices/register} 的请求体（见文档 02b §1.0）。
 *
 * <p><b>一次请求只登记药箱主控</b>：{@code deviceId} 由设备固件生成并固化，监护人扫码 /
 * 手填；系统不再建模传感器 / 摄像头子设备，因此没有 {@code sensors} 数组。
 *
 * <p><b>调用方是监护人</b>：{@code elderId} 是被监护的老人，由 Service 走
 * {@code require(elderId, WRITE_DEVICE)} 校验 —— 老人本人不能自注册。
 *
 * @param deviceId   药箱主控编号，同时是 MQTT 的 clientId
 * @param elderId    归属老人的业务 ID（{@code user.user_id}）
 * @param productKey 产品 key，Topic 第 2 层；留空时用配置项 {@code medbox.device.default-product-key}
 * @param timezone   设备本地时区，服药时间按此换算；留空默认 {@code Asia/Shanghai}
 */
public record DeviceRegisterRequest(

        @NotBlank(message = "deviceId 不能为空")
        @Pattern(regexp = "[A-Za-z0-9_-]{4,64}", message = "deviceId 需为 4~64 位字母、数字、下划线或短横线")
        String deviceId,

        @NotBlank(message = "elderId 不能为空")
        String elderId,

        @Size(max = 64, message = "productKey 超长")
        String productKey,

        @Size(max = 32, message = "timezone 超长")
        String timezone) {
}
