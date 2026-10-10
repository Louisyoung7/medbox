package com.medbox.server.common.security;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 细粒度操作权限 + **集中式权限矩阵**（见文档 01 第 3 章、02 的 1.3）。
 *
 * <p>校验顺序的第 ④ 步「该角色是否具备此操作权限」就落在 {@link #allows} 这一个方法上 ——
 * 矩阵写在这一个文件里，后续功能分支（{@code feat/medicine-core}、{@code feat/plan-core}、
 * {@code feat/alarm-core}、{@code feat/ai-chat} ...）只需在接口里声明自己要哪个
 * {@code Permission}，不再各自写 if 判断角色。
 *
 * <p>矩阵口径（行 = 访问身份，列 = 权限；未列出的组合一律 40301）：
 *
 * <pre>
 *                       ELDER  FAMILY  NURSE  DOCTOR
 *   READ_PLAN             ✅      ✅      ✅      ✅
 *   WRITE_PLAN                   ✅              ✅
 *   READ_MEDICINE         ✅      ✅      ✅      ✅
 *   WRITE_MEDICINE                ✅
 *   READ_RECORD           ✅      ✅      ✅      ✅
 *   WRITE_RECORD(补录)           ✅
 *   READ_ALARM            ✅      ✅      ✅      ✅
 *   HANDLE_ALARM                  ✅      ✅
 *   EXPORT_RECORD                 ✅      ✅
 *   CONFIG_LLM_KEY                ✅
 *   READ_CAPTURE          ✅      ✅
 *   WRITE_CAPTURE(删除)           ✅
 *   READ_DEVICE           ✅      ✅      ✅      ✅
 *   WRITE_DEVICE(命令)            ✅
 *   READ_ENV              ✅      ✅      ✅      ✅
 *   READ_ADHERENCE        ✅      ✅      ✅      ✅
 *   WRITE_DRUG_MANUAL                             ✅
 *   AI_CHAT               ✅      ✅
 *   READ_GUARDIAN         ✅      ✅      ✅      ✅
 * </pre>
 *
 * <p>依据：01 第 3 章《权限默认规则》；抓拍（READ_CAPTURE / WRITE_CAPTURE）对护理 / 医生**显式拒绝**，
 * 按 01 的 4.6 —— 用药图像属敏感健康数据，可见范围比默认规则更严格。
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

    /** 导出服药记录（护理 / 医生视角的报表）。 */
    EXPORT_RECORD,

    /** 为老人配置大模型 API Key。 */
    CONFIG_LLM_KEY,

    /** 查看服药抓拍图片（仅老人本人与 ACTIVE 监护人）。 */
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

    /** 维护药品禁忌知识库（说明书 / 禁忌条目）。 */
    WRITE_DRUG_MANUAL,

    /** AI 药品问答。 */
    AI_CHAT,

    /** 查看该老人的监护关系列表。 */
    READ_GUARDIAN;

    /** 矩阵 key：与 {@code RelationRole} 的枚举名一致（用字符串避免 common 层依赖 domain 枚举）。 */
    private static final String FAMILY = "FAMILY";
    private static final String DOCTOR = "DOCTOR";
    private static final String NURSE = "NURSE";

    /** 老人本人（{@code SELF}）：只读本人数据 + AI 问答 + 自己的抓拍。 */
    private static final Set<Permission> SELF_ALLOWED = EnumSet.of(
            READ_PLAN, READ_MEDICINE, READ_RECORD, READ_ALARM,
            READ_CAPTURE, READ_DEVICE, READ_ENV, READ_ADHERENCE,
            AI_CHAT, READ_GUARDIAN);

    /** 只读类权限：任何监护角色都能看（但抓拍除外，见 {@link #BY_RELATION_ROLE}）。 */
    private static final Set<Permission> READABLE = EnumSet.of(
            READ_PLAN, READ_MEDICINE, READ_RECORD, READ_ALARM,
            READ_DEVICE, READ_ENV, READ_ADHERENCE, READ_GUARDIAN);

    /** 监护关系角色 → 允许的操作权限。 */
    private static final Map<String, Set<Permission>> BY_RELATION_ROLE = Map.of(
            FAMILY, union(READABLE, EnumSet.of(
                    WRITE_PLAN, WRITE_MEDICINE, WRITE_RECORD,
                    HANDLE_ALARM, EXPORT_RECORD, CONFIG_LLM_KEY,
                    READ_CAPTURE, WRITE_CAPTURE, WRITE_DEVICE, AI_CHAT)),
            NURSE, union(READABLE, EnumSet.of(HANDLE_ALARM, EXPORT_RECORD)),
            DOCTOR, union(READABLE, EnumSet.of(WRITE_PLAN, WRITE_DRUG_MANUAL)));

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
            case GUARDIAN -> BY_RELATION_ROLE
                    .getOrDefault(identity.relationRole(), Set.of())
                    .contains(permission);
        };
    }

    private static Set<Permission> union(Set<Permission> base, Set<Permission> extra) {
        EnumSet<Permission> result = EnumSet.copyOf(base);
        result.addAll(extra);
        return result;
    }
}
