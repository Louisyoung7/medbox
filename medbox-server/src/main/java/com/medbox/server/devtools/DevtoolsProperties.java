package com.medbox.server.devtools;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 联调辅助配置（{@code medbox.devtools.*}，见文档 02 的 1.4 / 03 第 5 章）。
 *
 * <p>{@code enabled} 是总开关：默认 {@code false}，整个 {@code devtools} 包的端点都不装配
 * （与 {@code medbox.ws.debug-endpoint-enabled} 同一套路），本地联调才打开，生产零暴露面。
 *
 * <p>{@code seed} 段是 {@code POST /api/v1/_debug/seed} 要播种的最小数据集参数。
 * <b>会与真实数据相撞的 ID 一律加 {@code debug} 段</b>：{@code u-debug-guardian}（业务首个用户必是
 * {@code u-1001}）、{@code BOXA-DEBUG-01}（设备号可能等于真机序列号，且 {@code clearSeed} 会按它删设备）、
 * {@code s-debug-001}（业务首个库存必是 {@code s-1001}）。
 * 其余 {@code e-1001} / {@code m-205} / {@code p-3301} / {@code pi-9001} 不在业务序列生成值域内
 * （业务号恒为前缀 + 数字、且从 1001 起），天然隔离，故保留短 ID。
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
        private String guardianPassword = "123456";

        /** 调试监护人展示名。 */
        private String guardianName = "调试监护人";

        /**
         * 调试监护人业务 ID。
         *
         * <p><b>必须落在 {@code debug} 命名空间</b>：业务号由 {@code user_biz_id_seq} 从 1001 起生成
         * （清库后首个注册用户就是 {@code u-1001}）。沿用旧值会与真实账号撞唯一约束——幂等检查会把
         * 对方误判为"种子已存在"而整批跳过，{@code reset} 时更会把真实账号当残留删掉。
         */
        private String guardianUserId = "u-debug-guardian";

        /**
         * 调试老人业务 ID。
         *
         * <p>保留 {@code e-} 前缀即可：业务号恒为 {@code u-}<b>数字</b>（见 {@code UserMapper#nextUserId()}），
         * {@code e-1001} 永远不可能被业务生成，天然与真实账号隔离，无需加 {@code debug} 段。
         */
        private String elderUserId = "e-1001";

        /** 调试老人展示名。 */
        private String elderName = "调试老人";

        /** 调试老人手机号（同时也是登录账号）。 */
        private String elderPhone = "13800008888";

        /** 调试老人密码（明文，仅本地联调用，不进真实账号体系）。 */
        private String elderPassword = "123456";

        /**
         * 调试药箱主控设备 ID（同时是 MQTT clientId）。
         *
         * <p><b>同样避开真实设备号</b>：{@code clearSeed} 会按该 ID 及其 {@code -S-} 子设备删设备，
         * 用真机序列号做种子会误删真实设备。
         */
        private String deviceId = "BOXA-DEBUG-01";

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

        public String getElderPhone() {
            return elderPhone;
        }

        public void setElderPhone(String elderPhone) {
            this.elderPhone = elderPhone;
        }

        public String getElderPassword() {
            return elderPassword;
        }

        public void setElderPassword(String elderPassword) {
            this.elderPassword = elderPassword;
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
