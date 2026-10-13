package com.medbox.server.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 预热 MyBatis-Plus 的 {@code TableInfo} 缓存（**仅测试用**）。
 *
 * <p><b>为什么需要它</b>：Mapper 被 {@code @MockitoBean} 替换后，真正的
 * {@code MapperFactoryBean} 不会再走 {@code checkDaoConfig()}，MyBatis 的 {@code Configuration}
 * 里就没有这台 Mapper，{@code TableInfoHelper} 也就没为对应实体建过表信息。
 * 于是任何<b>立即解析列名</b>的 Lambda 条件都会抛
 * {@code MybatisPlusException: can not find lambda cache for this entity}。
 *
 * <p><b>哪些条件是立即解析的</b>（踩过的坑，别再踩）：
 * <ul>
 *   <li><b>{@code in(R column, Collection)} 会立即解析</b> —— 它要拼 {@code IN (...)} 的占位符，
 *       没法像 {@code eq} 那样把列名包成惰性 {@code SqlSegment}；</li>
 *   <li><b>{@code eq} / {@code orderByAsc} 等是惰性的</b> —— 列名要到生成 SQL 时才解析，
 *       而 Mapper 被打桩后根本走不到生成 SQL 那一步，所以看起来"能跑"。</li>
 * </ul>
 * 这正是"有的用例过了、有的莫名其妙 500"的原因，与业务代码无关 —— 生产环境 Mapper 是真 Bean，
 * 启动时就会把表信息建好。
 *
 * <p>用法：在 {@code @BeforeAll} / {@code @BeforeEach} 里
 * {@code TableInfoWarmup.init(Device.class, DeviceEvent.class);}
 */
public final class TableInfoWarmup {

    private TableInfoWarmup() {
    }

    /** 为指定实体建好表信息缓存；重复调用无副作用（{@code initTableInfo} 内部幂等）。 */
    public static void init(Class<?>... entityClasses) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        // MP 3.5.17 的 initTableInfo 收的是 MapperBuilderAssistant（内部用它建 ResultMap 等），
        // resource 传空串即可 —— 这里只为填表信息缓存，不做真正的 XML 解析
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        for (Class<?> entityClass : entityClasses) {
            TableInfoHelper.initTableInfo(assistant, entityClass);
        }
    }
}
