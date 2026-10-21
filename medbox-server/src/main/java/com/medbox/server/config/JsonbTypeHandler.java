package com.medbox.server.config;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL {@code JSONB} 列的 MyBatis 类型处理器基类（{@code feat/plan-core}）。
 *
 * <p><b>为什么自己写一个</b>：全仓此前没有 JSONB 读写（{@code med_plan.times} /
 * {@code med_plan.alarm_rule} 是头两个 JSONB 列），MyBatis 默认只会把 JSONB 当成
 * {@code String}/{@code PGobject} 原样吐出来，无法反序列化成 {@code List<String>} /
 * {@code Map<String,Boolean>}。本类用 Jackson 3（Boot 4 自带）做序列化。
 *
 * <p><b>为什么是泛型基类 + 具体子类</b>：字段级 {@code @TableField(typeHandler=...)}
 * 引用的是具体类，且 MyBatis 在构建结果映射时会用无参构造实例化它 —— 泛型无法在运行时
 * 凭空拿到 {@link JavaType}，所以子类用无参构造把具体类型焊死（见
 * {@link TimesJsonbTypeHandler} / {@link AlarmRuleJsonbTypeHandler}）。
 *
 * <p><b>ObjectMapper 是内部独立实例</b>：类型处理器由 MyBatis 实例化（不走 Spring 注入），
 * 这里直接 {@code JsonMapper.builder().build()}，只用来序列化简单 POJO（List / Map），
 * 与全局 Jackson 配置无关、零耦合。
 *
 * <p><b>挂载方式</b>：实体字段上加 {@code @TableField(typeHandler = XxxHandler.class)}，
 * 且类级 {@code @TableName(autoResultMap = true)}，否则 MyBatis-Plus 生成的结果映射
 * 不会带这个处理器、读出来仍是 {@code PGobject}。
 */
public abstract class JsonbTypeHandler<T> extends BaseTypeHandler<T> {

    /** 内部独立 ObjectMapper（Jackson 3）。 */
    protected static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final JavaType javaType;

    protected JsonbTypeHandler(JavaType javaType) {
        this.javaType = javaType;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject pg = new PGobject();
        pg.setType("jsonb");
        try {
            pg.setValue(MAPPER.writeValueAsString(parameter));
        } catch (Exception e) {
            throw new SQLException("JSONB 序列化失败", e);
        }
        ps.setObject(i, pg);
    }

    @Override
    public T getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return from(rs.getObject(columnName));
    }

    @Override
    public T getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return from(rs.getObject(columnIndex));
    }

    @Override
    public T getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return from(cs.getObject(columnIndex));
    }

    @SuppressWarnings("unchecked")
    private T from(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        String json = value instanceof PGobject pg ? pg.getValue() : value.toString();
        try {
            return (T) MAPPER.readValue(json, javaType);
        } catch (Exception e) {
            throw new SQLException("JSONB 解析失败: " + json, e);
        }
    }
}
