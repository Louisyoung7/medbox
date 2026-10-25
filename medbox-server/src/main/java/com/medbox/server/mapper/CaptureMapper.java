package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.Capture;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

/**
 * {@code capture} 表的 Mapper（见文档 06 的 2.12）。
 *
 * <p><b>演进</b>：{@code feat/wrong-drug-alarm} 只加了 {@link #markLowConfidence}（低置信度标 {@code reason}）；
 * 本分支（{@code feat/capture-core}）补齐其余读写：业务号取号、去重查询、分页、详情、首读标记、删除。
 * 合并点已记入 CHANGELOG。
 */
@Mapper
public interface CaptureMapper extends BaseMapper<Capture> {

    /**
     * 生成抓拍业务号：{@code cap-1001}（序列见 V14 的 {@code capture_biz_id_seq}）。
     */
    @Select("SELECT 'cap-' || nextval('capture_biz_id_seq')")
    String nextCaptureId();

    /**
     * 去重查询：同一 {@code planId} 在 {@code capturedAt} 前后 {@code windowSeconds} 秒内的已有抓拍。
     *
     * <p>用 {@code ABS(EXTRACT(EPOCH FROM (captured_at - :capturedAt)))} 算时间差（秒），
     * MyBatis 注解里 {@code <=} 用 CDATA 包裹避免被当成动态标签解析。命中返回第一条即视为重复。
     */
    @Select("SELECT * FROM capture WHERE plan_id = #{planId} "
            + "AND ABS(EXTRACT(EPOCH FROM (captured_at - #{capturedAt}))) <![CDATA[<=]]> #{windowSeconds} "
            + "ORDER BY captured_at LIMIT 1")
    Capture selectByPlanWithinWindow(@Param("planId") String planId,
                                     @Param("capturedAt") OffsetDateTime capturedAt,
                                     @Param("windowSeconds") int windowSeconds);

    /** 按业务号查单条（走 {@code capture_id} 唯一约束，命中 {@code idx_capture_elder_time} 前缀）。 */
    @Select("SELECT * FROM capture WHERE capture_id = #{captureId}")
    Capture selectByCaptureId(@Param("captureId") String captureId);

    /** 首次被读取时写 {@code viewed_at} 并重算 {@code expire_at}（查看只会让过期提前，不会延长）。 */
    @Update("UPDATE capture SET viewed_at = #{viewedAt}, expire_at = #{expireAt} "
            + "WHERE capture_id = #{captureId}")
    void markViewed(@Param("captureId") String captureId,
                    @Param("viewedAt") OffsetDateTime viewedAt,
                    @Param("expireAt") OffsetDateTime expireAt);

    /** 删除单条（同时由 Service 负责删磁盘文件）。 */
    @Delete("DELETE FROM capture WHERE capture_id = #{captureId}")
    int deleteByCaptureId(@Param("captureId") String captureId);

    /**
     * 低置信度标记（交人工复核）：把指定抓拍的 {@code reason} 改成给定值（如 {@code LOW_CONFIDENCE}）。
     *
     * @return 受影响行数（抓拍不存在时为 0，调用方据此判断是否需要告警，安全）
     */
    @Update("UPDATE capture SET reason = #{reason} WHERE capture_id = #{captureId}")
    int markLowConfidence(@Param("captureId") String captureId, @Param("reason") String reason);
}
