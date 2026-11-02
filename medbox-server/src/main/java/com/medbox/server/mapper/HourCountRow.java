package com.medbox.server.mapper;

import lombok.Data;

/** 按小时分组计数行（missed by hour），字段与 SQL 别名 hr / cnt 对应。 */
@Data
public class HourCountRow {

    private Integer hr;
    private long cnt;
}
