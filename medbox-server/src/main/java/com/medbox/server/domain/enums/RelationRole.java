package com.medbox.server.domain.enums;

import java.util.Locale;

/**
 * 监护关系角色（{@code guardian_relation.role}，见文档 06 的 2.2）。
 *
 * <p><b>与 {@link UserRole} 的区别</b>：{@code user.role} 只有 ELDER / GUARDIAN 两种（注册接口只接受这两个，
 * 见文档 02 第 3 章）；社区护理 / 家庭医生不是注册角色，它们是**监护关系上的角色**，由老人在确认监护
 * 关系时指定（见文档 01 第 3 章）。因此"某人能不能改某老人的计划"要看两层：他是不是该老人的
 * 监护人（关系是否存在且 ACTIVE）+ 他在关系里是什么角色。
 *
 * <p>列可空（见 06 的 2.2 {@code role VARCHAR(16)}，无 NOT NULL）：历史 / 脏数据可能没填，
 * 判定时按 {@link #DEFAULT_ROLE}（FAMILY）兜底并打日志，不让空值变成"放行"或"崩溃"。
 */
public enum RelationRole {

    /** 家属 / 子女：对 ACTIVE 关系下的老人可读可写（计划、药品、库存、告警处理、阈值、AI Key）。 */
    FAMILY,

    /** 家庭医生：制定 / 修改服药计划 + 维护药品禁忌知识库 + 查看依从性报告；不处理告警、不导出。 */
    DOCTOR,

    /** 社区护理人员：所辖老人只读 + 处理告警 + 导出记录；不可改计划与药品。 */
    NURSE;

    /** 兜底角色：{@code role} 为 NULL 时按家属处理。 */
    public static final RelationRole DEFAULT_ROLE = FAMILY;

    /** 宽松解析（忽略大小写与首尾空格）；不是合法角色返回 {@code null}，由调用方决定报错码。 */
    public static RelationRole parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return RelationRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
