package com.medbox.server.common;

import java.io.IOException;
import java.io.Writer;

/**
 * 极简 CSV 工具（仅本服务导出服药报告用，见文档 02 第 7 章 / 07 的 {@code feat/record-report}）。
 *
 * <p>不做流式分块（报告量级小，直接写响应流），只负责值的转义与一行写出：
 * 含双引号 / 逗号 / 换行的值按 RFC 4180 用双引号包裹并把内部双引号转义为两个双引号。
 */
public final class CsvUtil {

    private CsvUtil() {
    }

    /** 转义单个单元格：null → 空串；特殊字符 → 双引号包裹。 */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf('"') >= 0 || value.indexOf(',') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** 写出一行（单元格用逗号分隔，行尾 CRLF）。 */
    public static void writeRow(Writer writer, String... cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(escape(cells[i]));
        }
        writer.write("\r\n");
    }
}
