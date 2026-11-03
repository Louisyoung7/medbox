package com.medbox.server.mapper;

import lombok.Data;

/** 按老人分组计数行（taken / missed by elder），字段与 SQL 别名 elder_id / elder_name / cnt 对应。 */
@Data
public class ElderCountRow {

    private String elderId;
    private String elderName;
    private long cnt;
}
