package com.medbox.server.mqtt.payload;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 上行·库存变更 {@code up/inventory}（见文档 03 第 3 章）。
 *
 * <p>按药品不按仓位。<b>生产日期与有效期不从设备上报</b>，由小程序录入
 * （{@code production_date} / {@code expiry_date} 是过期判定的唯一依据）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class InventoryPayload extends BaseUpPayload {

    private String medicineId;

    /** 变更后的库存数量。 */
    private Integer quantity;

    /** 本次变化量（服药 -1 / 补药 +n）。 */
    private Integer delta;
}
