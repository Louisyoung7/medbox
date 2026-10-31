package com.medbox.server.controller;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.R;
import com.medbox.server.dto.record.RecordConfirmRequest;
import com.medbox.server.dto.record.RecordResponse;
import com.medbox.server.common.CsvUtil;
import com.medbox.server.service.RecordService;
import java.io.IOException;
import java.io.Writer;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
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

    /**
     * GET /records/export：以 CSV 文件下载服药报告（护理 / 医生视角）。
     *
     * <p>权限 {@code EXPORT_RECORD} 在 {@link RecordService#exportRecords} 内校验（老人 / 越权 / PENDING
     * → 40301 / 40302）。{@code from}/{@code to} 可选：都不传 → 最近 30 天；仅传其一 → 另一边界按
     * 30 天窗口推导。响应体是 CSV 流，不走 {@code R<T>} 包装。
     */
    @GetMapping("/export")
    public void export(@RequestParam("elderId") String elderId,
                       @RequestParam(value = "from", required = false) String from,
                       @RequestParam(value = "to", required = false) String to,
                       HttpServletResponse response) throws IOException {
        OffsetDateTime fromTime = parse(from);
        OffsetDateTime toTime = parse(to);
        OffsetDateTime now = OffsetDateTime.now();
        if (fromTime == null && toTime == null) {
            toTime = now;
            fromTime = now.minusDays(30);
        } else if (fromTime == null) {
            fromTime = (toTime != null ? toTime : now).minusDays(30);
        } else if (toTime == null) {
            toTime = now;
        }

        List<RecordResponse> records = recordService.exportRecords(elderId, fromTime, toTime);

        String filename = "medication-records-" + elderId + "-"
                + fromTime.toLocalDate() + "-" + toTime.toLocalDate() + ".csv";
        response.setContentType("text/csv; charset=utf-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.setCharacterEncoding("utf-8");

        DateTimeFormatter iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        try (Writer writer = response.getWriter()) {
            writer.write("﻿"); // UTF-8 BOM，便于 Excel 正确识别中文
            CsvUtil.writeRow(writer, "记录ID", "老人ID", "计划名", "药品名称", "计划时间",
                    "实际时间", "是否按时", "结论", "来源", "剂量", "抓拍ID", "设备ID");
            for (RecordResponse r : records) {
                CsvUtil.writeRow(writer,
                        str(r.recordId()), str(r.elderId()), str(r.planName()), str(r.medicineName()),
                        fmt(r.planTime(), iso), fmt(r.actualTime(), iso),
                        bool(r.onTime()), str(r.faceResult()), str(r.source()),
                        str(r.actualDose()), str(r.captureId()), str(r.deviceId()));
            }
        }
    }

    private static String str(String value) {
        return value == null ? "" : value;
    }

    private static String fmt(OffsetDateTime value, DateTimeFormatter formatter) {
        return value == null ? "" : value.format(formatter);
    }

    private static String bool(Boolean value) {
        return value == null ? "" : value.toString();
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
