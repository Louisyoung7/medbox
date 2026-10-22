package com.medbox.server.service;

import java.util.Objects;

/**
 * 计划变更事件：{@link PlanService} 在写事务提交后发布，由 {@code MqttPlanScheduleSync} 监听并下行到设备。
 *
 * <p><b>只带"定位 + 动作"三类信息</b>（planId / elderId / op），下行载荷由监听器从库里回查组装 ——
 * 既避免 Service 层直接依赖 mqtt 下行包，也避免把整条计划（含 items）塞进事件体。
 * UPSERT 时监听器回查计划头 + 明细；DELETE 时只用到 planId。
 *
 * <p><b>为什么是事务提交后</b>：监听器用 {@code @TransactionalEventListener(AFTER_COMMIT)}，
 * 保证"计划已落库"之后才下发，回查一定能拿到已提交数据；下行失败也绝不回滚计划落库事务。
 */
public class PlanSyncEvent {

    /** 下行动作：UPSERT = 新建/修改/启用（把计划推给设备）；DELETE = 删除/停用（让设备停该计划提醒）。 */
    public enum Op { UPSERT, DELETE }

    private final String planId;
    private final String elderId;
    private final Op op;

    public PlanSyncEvent(String planId, String elderId, Op op) {
        this.planId = Objects.requireNonNull(planId, "planId");
        this.elderId = Objects.requireNonNull(elderId, "elderId");
        this.op = Objects.requireNonNull(op, "op");
    }

    public String getPlanId() {
        return planId;
    }

    public String getElderId() {
        return elderId;
    }

    public Op getOp() {
        return op;
    }
}
