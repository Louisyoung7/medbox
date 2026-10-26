package com.medbox.server.dto.device;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * 下发控制命令请求（feat/device-command）。
 *
 * <p><b>不收 {@code operator} 字段</b>：操作人由服务端取鉴权用户留痕，不信客户端传值；
 * 客户端（mp-device-extra）多传的 {@code operator} 由 Jackson 忽略，不算错误。
 */
public class CommandSendRequest {

    /** 命令白名单：BUZZ / UNLOCK / REBOOT / CALIBRATE（见 {@code DeviceCmd}）。 */
    @NotBlank(message = "cmd 不能为空")
    private String cmd;

    /** 命令参数（如 {"durationSec":5}），可空按 {} 透传给设备。 */
    private Map<String, Object> params;

    public String getCmd() {
        return cmd;
    }

    public void setCmd(String cmd) {
        this.cmd = cmd;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }
}
