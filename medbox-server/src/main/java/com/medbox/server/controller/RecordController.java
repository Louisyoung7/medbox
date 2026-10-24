package com.medbox.server.controller;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.R;
import com.medbox.server.dto.record.RecordConfirmRequest;
import com.medbox.server.dto.record.RecordResponse;
import com.medbox.server.service.RecordService;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服药记录接口（见文档 02 第 7 章、07 的 {@code feat/record-core}）。
 *
 * <p>只做参数绑定与 {@code R<T>} 包装：权限 / 校验 / 分页筛选 / join 名称 / 补录新建全在
 * {@link RecordService}；未登录由 {@code AuthInterceptor} 统一兜 40101。
 *
 * <p>三个端点：列表（分页筛选）、详情（关联计划与抓拍）、补录 / 确认（单接口校准 + 新建）。
 */
@RestController
@RequestMapping("/api/v1/records")
public class RecordController {

    private final RecordService recordService;

    public RecordController(RecordService recordService) {
        this.recordService = recordService;
    }

    /** GET /records?elderId=&from=&to=：分页列表（按实际服药时间倒序，可加时间窗）。 */
    @GetMapping
    public R<PageResult<RecordResponse>> list(
            @RequestParam("elderId") String elderId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return R.ok(recordService.list(elderId, parse(from), parse(to), page, size));
    }

    /** GET /records/{recordId}：详情（关联计划名 / 药名 / 抓拍 ID）。 */
    @GetMapping("/{recordId}")
    public R<RecordResponse> detail(@PathVariable String recordId) {
        return R.ok(recordService.detail(recordId));
    }

    /** POST /records/{recordId}/confirm：监护人补录 / 校准（命中则校准，未命中则新建）。 */
    @PostMapping("/{recordId}/confirm")
    public R<RecordResponse> confirm(@PathVariable String recordId,
                                     @RequestBody RecordConfirmRequest request) {
        return R.ok(recordService.confirm(recordId, request));
    }

    /** from/to 解析为 ISO-8601（OffsetDateTime）；空串返回 null，格式非法 → 40001。 */
    private static OffsetDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException e) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "from/to 需为 ISO-8601 时间: " + value);
        }
    }
}
