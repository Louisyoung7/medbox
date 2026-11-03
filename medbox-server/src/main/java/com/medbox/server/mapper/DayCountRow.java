package com.medbox.server.mapper;

import lombok.Data;

/** 按天分组计数行（taken / missed by day），字段与 SQL 别名 day / cnt 对应。 */
@Data
public class DayCountRow {

    private String day;
    private long cnt;
}
