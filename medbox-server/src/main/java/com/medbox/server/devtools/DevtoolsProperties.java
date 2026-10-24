package com.medbox.server.devtools;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 联调辅助配置（{@code medbox.devtools.*}，见文档 02 的 1.4 / 03 第 5 章）。
 *
 * <p>{@code enabled} 是总开关：默认 {@code false}，整个 {@code devtools} 包的端点都不装配
 * （与 {@code medbox.ws.debug-endpoint-enabled} 同一套路），本地联调才打开，生产零暴露面。
 *
 * <p>{@code seed} 段是 {@code POST /api/v1/_debug/seed} 要播种的最小数据集参数；
 * 业务 ID 与文档示例对齐（{@code u-1001} / {@code e-1001} / {@code BOXA1001} / {@code m-205} /
 * {@code p-3301} / {@code pi-9001}），使 02 §1.4 与 03 §5 的 curl / mosquitto_pub 示例可直接跑通。
 */
@ConfigurationProperties(prefix = "medbox.devtools")
public class DevtoolsProperties {

    /** 调试端点总开关，默认关闭。 */
    private boolean enabled = false;

    /** 种子数据参数。 */
    private Seed seed = new Seed();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Seed getSeed() {
        return seed;
    }

    public void setSeed(Seed seed) {
        this.seed = seed;
    }

    /** 种子数据集的可配置参数（业务 ID 与文档示例对齐）。 */
    public static class Seed {

        /** 调试监护人手机号（同时也是登录账号）。 */
        private String guardianPhone = "13800001234";

        /** 调试监护人密码（明文，仅本地联调用，不进真实账号体系）。 */
        private String guardianPassword = "Dev@123456";

        /** 调试监护人展示名。 */
        private String guardianName = "调试监护人";

        /** 调试监护人业务 ID。 */
        private String guardianUserId = "u-1001";

        /** 调试老人业务 ID。 */
        private String elderUserId = "e-1001";

        /** 调试老人展示名。 */
        private String elderName = "调试老人";

        /** 调试药箱主控设备 ID（同时是 MQTT clientId）。 */
        private String deviceId = "BOXA1001";

        /** 调试药箱产品 key（Topic 第 2 层）。 */
        private String productKey = "medbox-box";

        /** 调试设备本地时区。 */
        private String timezone = "Asia/Shanghai";

        public String getGuardianPhone() {
            return guardianPhone;
        }

        public void setGuardianPhone(String guardianPhone) {
            this.guardianPhone = guardianPhone;
        }

        public String getGuardianPassword() {
            return guardianPassword;
        }

        public void setGuardianPassword(String guardianPassword) {
            this.guardianPassword = guardianPassword;
        }

        public String getGuardianName() {
            return guardianName;
        }

        public void setGuardianName(String guardianName) {
            this.guardianName = guardianName;
        }

        public String getGuardianUserId() {
            return guardianUserId;
        }

        public void setGuardianUserId(String guardianUserId) {
            this.guardianUserId = guardianUserId;
        }

        public String getElderUserId() {
            return elderUserId;
        }

        public void setElderUserId(String elderUserId) {
            this.elderUserId = elderUserId;
        }

        public String getElderName() {
            return elderName;
        }

        public void setElderName(String elderName) {
            this.elderName = elderName;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(String deviceId) {
            this.deviceId = deviceId;
        }

        public String getProductKey() {
            return productKey;
        }

        public void setProductKey(String productKey) {
            this.productKey = productKey;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }
    }
}
