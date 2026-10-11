package com.medbox.server.ws;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 订阅主题的语法与解析（见文档 04 第 1 章）。
 *
 * <p><b>本分支只支持 {@code elder:{elderId}}</b>：文档 04 的示例与帧结构里 topic 一律是这个形态。
 * 07 清单虽然写了"按订阅主题（所监护老人 / 设备）鉴权"，但设备维度要先把 {@code deviceId} 解析成
 * 归属老人，依赖 {@code DeviceOwnerResolver} —— 它目前只有接口、实现留给 {@code feat/device-core}
 * （见 {@code AccessService.requireDevice}）。故 {@code device:xxx} 本分支**明确拒绝并给出原因**，
 * 而不是静默忽略：静默忽略会让调用方以为订阅成功了。
 *
 * <p>多个主题用逗号分隔（{@code topics=elder:e-1001,elder:e-1002}），重复项去重、保持顺序。
 */
public final class WsTopic {

    /** 老人维度主题前缀。 */
    public static final String ELDER_PREFIX = "elder:";

    /** 设备维度主题前缀（<b>本分支不支持</b>，见类注释）。 */
    public static final String DEVICE_PREFIX = "device:";

    private WsTopic() {
    }

    /** 拼出老人主题。 */
    public static String elder(String elderId) {
        return ELDER_PREFIX + elderId;
    }

    /**
     * 拆分握手参数 {@code topics}：逗号分隔、去空白、去重、保持顺序。
     *
     * @return 主题列表；参数为 null / 空白 / 全是逗号时返回空列表（由调用方决定报 400 还是放行）
     */
    public static List<String> split(String raw) {
        Set<String> unique = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String topic = part.trim();
                if (!topic.isEmpty()) {
                    unique.add(topic);
                }
            }
        }
        return new ArrayList<>(unique);
    }

    /** 取主题里的老人 ID；非 {@code elder:} 主题返回 {@code empty}。 */
    public static Optional<String> elderIdOf(String topic) {
        if (topic == null) {
            return Optional.empty();
        }
        String value = topic.trim();
        if (!value.startsWith(ELDER_PREFIX)) {
            return Optional.empty();
        }
        String elderId = value.substring(ELDER_PREFIX.length()).trim();
        return elderId.isEmpty() ? Optional.empty() : Optional.of(elderId);
    }

    /**
     * 主题是否合法；返回 {@code null} 表示合法，否则返回给客户端看的拒绝原因。
     *
     * <p>刻意不抛异常：握手阶段要一次性把"为什么被拒"写进 HTTP 响应体，异常路径反而绕。
     */
    public static String rejectionReason(String topic) {
        if (topic == null || topic.isBlank()) {
            return "订阅主题不能为空";
        }
        String value = topic.trim();
        if (value.startsWith(DEVICE_PREFIX)) {
            return "device 主题暂不支持：设备归属解析待 feat/device-core 接入";
        }
        if (!value.startsWith(ELDER_PREFIX)) {
            return "订阅主题格式错误，仅支持 elder:{elderId}";
        }
        if (elderIdOf(value).isEmpty()) {
            return "订阅主题缺少老人 ID";
        }
        return null;
    }
}
