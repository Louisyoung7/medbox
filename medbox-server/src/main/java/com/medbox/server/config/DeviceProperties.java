package com.medbox.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 设备注册与事件流水的配置（{@code medbox.device.*}，见文档 02 第 4 章 / 06 的 2.16）。
 *
 * <p>与 {@code medbox.mqtt.*} 的结构化风格保持一致：能改的都放进配置，
 * 避免把"每页几条""默认时区"这类常量散落在 Service 里。
 */
@ConfigurationProperties(prefix = "medbox.device")
public class DeviceProperties {

    /**
     * 产品 key 的默认值，用于注册请求没填 {@code productKey} 时兜底。
     *
     * <p>它是 Topic 的第 2 层（{@code medbox/{productKey}/{deviceId}/...}），
     * 因此**不能留空** —— 留空会让下行订阅主题拼不出来。
     */
    private String defaultProductKey = "medbox-box";

    /** 设备本地时区的默认值（服药时间按此换算，见文档 06 的 2.6.1）。 */
    private String defaultTimezone = "Asia/Shanghai";

    /** 事件流水的默认每页条数。 */
    private int eventPageSize = 20;

    /** 事件流水的每页上限：防深分页拖垮数据库，也防客户端一次拉全量。 */
    private int eventMaxPageSize = 100;

    public String getDefaultProductKey() {
        return defaultProductKey;
    }

    public void setDefaultProductKey(String defaultProductKey) {
        this.defaultProductKey = defaultProductKey;
    }

    public String getDefaultTimezone() {
        return defaultTimezone;
    }

    public void setDefaultTimezone(String defaultTimezone) {
        this.defaultTimezone = defaultTimezone;
    }

    public int getEventPageSize() {
        return eventPageSize;
    }

    public void setEventPageSize(int eventPageSize) {
        this.eventPageSize = eventPageSize;
    }

    public int getEventMaxPageSize() {
        return eventMaxPageSize;
    }

    public void setEventMaxPageSize(int eventMaxPageSize) {
        this.eventMaxPageSize = eventMaxPageSize;
    }
}
