package com.medbox.server.dto.device;

import com.medbox.server.domain.enums.DeviceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /devices/register} 的请求体（见文档 02 第 4 章）。
 *
 * <p><b>一次请求登记一台药箱主控 + 其下全部传感器子设备</b>：子设备没有独立的 MQTT 连接、
 * 也没有注册入口，只能随主控一起登记（文档 02 第 4 章表下说明）。因此 {@code sensors}
 * 是数组，且**只允许子设备类型**（{@code MAIN} 会被拒）。
 *
 * <p><b>调用方是监护人</b>：{@code elderId} 是被监护的老人，由 Service 走
 * {@code require(elderId, WRITE_DEVICE)} 校验 —— 老人本人不能自注册
 * （{@code WRITE_DEVICE} 只对 FAMILY 监护人开放）。
 *
 * @param deviceId   药箱主控编号，同时是 MQTT 的 clientId（文档 03 第 1 章）
 * @param elderId    归属老人的业务 ID（{@code user.user_id}）
 * @param productKey 产品 key，Topic 第 2 层；留空时用配置项 {@code medbox.device.default-product-key}
 * @param timezone   设备本地时区，服药时间按此换算；留空默认 {@code Asia/Shanghai}
 * @param sensors    随主控一并登记的传感器子设备
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
        String timezone,

        @Valid
        @Size(max = 16, message = "子设备最多 16 个")
        List<SensorItem> sensors) {

    /**
     * 随主控登记的传感器子设备。
     *
     * @param deviceId   子设备编号；要求与主控同风格，便于一眼看出归属（如 {@code BOXA1001-S-TH01}）
     * @param deviceType 只能是 {@code TEMP_HUMI} / {@code LIGHT} / {@code CAMERA}；
     *                   {@code MAIN} 属于药箱主控，不允许出现在子设备里
     */
    public record SensorItem(

            @NotBlank(message = "子设备 deviceId 不能为空")
            @Pattern(regexp = "[A-Za-z0-9_-]{4,64}",
                    message = "子设备 deviceId 需为 4~64 位字母、数字、下划线或短横线")
            String deviceId,

            @NotNull(message = "子设备 deviceType 不能为空")
            DeviceType deviceType) {
    }
}
