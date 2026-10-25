package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.Capture;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * {@code capture} 表的 Mapper（见文档 06 的 2.12）。
 *
 * <p><b>本分支（feat/wrong-drug-alarm）最小可用</b>：仅新增 {@link #markLowConfidence}——
 * 低置信度识别时把指定抓拍的 {@code reason} 标记为 LOW_CONFIDENCE。行不存在则 0 影响、安全。
 * 其余 CRUD 由后续 {@code feat/capture-core} 在此扩展（合并点已记入 CHANGELOG）。
 */
@Mapper
public interface CaptureMapper extends BaseMapper<Capture> {

    /**
     * 标记抓拍为低置信度（交人工复核）。
     *
     * @return 受影响行数（抓拍不存在时为 0，调用方据此判断是否需要告警，安全）
     */
    @Update("UPDATE capture SET reason = #{reason} WHERE capture_id = #{captureId}")
    int markLowConfidence(@Param("captureId") String captureId, @Param("reason") String reason);
}
