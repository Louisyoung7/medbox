package com.medbox.server.devtools.dto;

import java.util.List;

/**
 * 种子数据结果：返回本次播种出的业务 ID 与监护人登录 token，便于文档示例直接复用。
 *
 * <p>{@code created=false} 表示种子已存在、本次跳过写入（幂等等价）；token 每次都重新签发，
 * 方便长时间后重新拿到有效凭证。
 */
public record SeedResult(

        /** 是否本次新建（false = 已存在，跳过）。 */
        boolean created,

        /** 调试监护人业务 ID。 */
        String guardianUserId,

        /** 调试监护人手机号（登录账号）。 */
        String guardianPhone,

        /** 监护人 access token（2 小时），可直接用于 {@code Authorization: Bearer}。 */
        String guardianToken,

        /** 调试老人业务 ID。 */
        String elderUserId,

        /** 调试药箱主控设备 ID。 */
        String deviceId,

        /** 播种出的药品业务 ID 列表（含公共药与私有药）。 */
        List<String> medicineIds,

        /** 播种出的库存业务 ID。 */
        String stockId,

        /** 播种出的计划业务 ID。 */
        String planId,

        /** 播种出的计划项业务 ID。 */
        String planItemId
) {
}
