package com.medbox.server.common.security;

import java.util.EnumSet;
import java.util.Set;

/**
 * 细粒度操作权限 + **集中式权限矩阵**（见文档 01 第 3 章、02 的 1.3）。
 *
 * <p>校验顺序的第 ④ 步「该角色是否具备此操作权限」就落在 {@link #allows} 这一个方法上 ——
 * 矩阵写在这一个文件里，后续功能分支只需在接口里声明自己要哪个 {@code Permission}，不再各自写 if 判断角色。
 *
 * <p>矩阵口径（行 = 访问身份，列 = 权限；未列出的组合一律 40301）：
 * 监护人已**统一为单一 {@code GUARDIAN} 角色**（不再区分 FAMILY / NURSE / DOCTOR，见文档 10 §1.2、
 * 06 的 2.2 {@code guardian_relation} 无 {@code role} 列），故矩阵只有两行。
 *
 * <pre>
 *                       SELF(老人本人)   GUARDIAN(统一监护人)
 *   READ_PLAN             ✅              ✅
 *   READ_MEDICINE        ✅              ✅
 *   READ_RECORD          ✅              ✅
 *   READ_ALARM           ✅              ✅
 *   READ_DEVICE          ✅              ✅
 *   READ_ENV             ✅              ✅
 *   READ_ADHERENCE       ✅              ✅
 *   READ_GUARDIAN        ✅              ✅
 *   READ_CAPTURE         ✅              ✅
 *   WRITE_PLAN                           ✅
 *   WRITE_MEDICINE                       ✅
 *   WRITE_RECORD                         ✅
 *   HANDLE_ALARM                         ✅
 *   EXPORT_RECORD                        ✅
 *   CONFIG_LLM_KEY                       ✅
 *   WRITE_ALARM_SETTINGS                 ✅
 *   WRITE_CAPTURE                        ✅
 *   WRITE_DEVICE                         ✅
 *   AI_INSIGHT           ✅              ✅
 * </pre>
 *
 * <p>依据：02 §1.3.1 权限矩阵（共 19 项，含 {@code AI_INSIGHT}）。抓拍（READ_CAPTURE / WRITE_CAPTURE）
 * 对老人本人与监护人都可见（用药图像属敏感健康数据，但两类身份均授权，越权由关系校验兜底）。
 */
public enum Permission {

    /** 查看服药计划。 */
    READ_PLAN,

    /** 创建 / 修改 / 停用 / 删除服药计划。 */
    WRITE_PLAN,

    /** 查看药品档案。 */
    READ_MEDICINE,

    /** 新建 / 修改 / 删除药品档案与库存（含补药入库）。 */
    WRITE_MEDICINE,

    /** 查看服药记录。 */
    READ_RECORD,

    /** 监护人手工补录 / 确认服药记录。 */
    WRITE_RECORD,

    /** 查看告警。 */
    READ_ALARM,

    /** 处理告警（置为已处理，闭环）。 */
    HANDLE_ALARM,

    /** 导出服药记录（监护人视角的报表）。 */
    EXPORT_RECORD,

    /** 为老人配置大模型 API Key。 */
    CONFIG_LLM_KEY,

    /** 配置老人级告警阈值（环境上下限 / 漏服容忍），仅监护人为老人代配。 */
    WRITE_ALARM_SETTINGS,

    /** 查看服药抓拍图片（老人本人与监护人）。 */
    READ_CAPTURE,

    /** 删除抓拍图片。 */
    WRITE_CAPTURE,

    /** 查看设备与子设备状态。 */
    READ_DEVICE,

    /** 下发设备控制命令。 */
    WRITE_DEVICE,

    /** 查看环境遥测。 */
    READ_ENV,

    /** 查看依从性统计。 */
    READ_ADHERENCE,

    /** AI 洞察（按需 / 周期分析，仅本机结构化数据，不落库、不自动执行，见文档 05）。 */
    AI_INSIGHT,

    /** 查看该老人的监护关系列表。 */
    READ_GUARDIAN;

    /**
     * 老人本人（{@code SELF}）：只读本人数据 + AI 问答 + 自己的抓拍。
     * 等价于「全部权限 − 九项写操作」。
     */
    private static final EnumSet<Permission> GUARDIAN_WRITES = EnumSet.of(
            WRITE_PLAN, WRITE_MEDICINE, WRITE_RECORD, HANDLE_ALARM, EXPORT_RECORD,
            CONFIG_LLM_KEY, WRITE_ALARM_SETTINGS, WRITE_CAPTURE, WRITE_DEVICE);

    /** 老人本人允许的操作：除写操作外全部放行。 */
    private static final Set<Permission> SELF_ALLOWED =
            EnumSet.complementOf(GUARDIAN_WRITES);

    /** 监护人（统一单一角色）允许的操作：全部 19 项。 */
    private static final Set<Permission> GUARDIAN_ALLOWED = EnumSet.allOf(Permission.class);

    /**
     * 第 ④ 步：该访问身份是否具备此操作权限。
     *
     * <p>只做纯矩阵查表，不查库、不判登录态（第 ① 步）与关系状态（第 ②③ 步）—— 那三步在
     * {@code AccessService} 里先短路掉了。未知角色（脏数据）一律返回 {@code false}。
     */
    public static boolean allows(AccessIdentity identity, Permission permission) {
        if (identity == null || permission == null) {
            return false;
        }
        return switch (identity.kind()) {
            case SELF -> SELF_ALLOWED.contains(permission);
            case GUARDIAN -> GUARDIAN_ALLOWED.contains(permission);
        };
    }
}
