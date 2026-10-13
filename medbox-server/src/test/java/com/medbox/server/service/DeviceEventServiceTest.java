package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.config.DeviceProperties;
import com.medbox.server.domain.DeviceEvent;
import com.medbox.server.domain.enums.DeviceEventType;
import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.device.DeviceEventResponse;
import com.medbox.server.mapper.DeviceEventMapper;
import com.medbox.server.support.MutableClock;
import com.medbox.server.support.TableInfoWarmup;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

/**
 * 设备事件流水（{@link DeviceEventService}，见文档 06 的 2.16）。
 *
 * <p>两条主线：① 写入是**旁路**，任何失败都不能阻断登记 / 状态更新这些主链路；
 * ② 分页参数要拦住明显的调用错误，同时把"多要一点"静默裁到上限。
 */
class DeviceEventServiceTest {

    private static final String DEVICE_ID = "BOXA1001";

    private DeviceEventMapper mapper;
    private DeviceProperties props;
    private MutableClock clock;
    private DeviceEventService service;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(DeviceEvent.class);
        mapper = mock(DeviceEventMapper.class);
        props = new DeviceProperties();
        clock = MutableClock.at(1_760_000_000_000L);
        service = new DeviceEventService(mapper, props, clock);
    }

    // ————————————————— 写入 —————————————————

    @Test
    @DisplayName("写入事件：字段齐全，时间取自注入的 Clock")
    void 写入事件() {
        service.record(DEVICE_ID, DeviceEventType.REGISTERED, null);

        ArgumentCaptor<DeviceEvent> captor = ArgumentCaptor.forClass(DeviceEvent.class);
        verify(mapper).insert(captor.capture());
        DeviceEvent saved = captor.getValue();

        assertEquals(DEVICE_ID, saved.getDeviceId());
        assertEquals(DeviceEventType.REGISTERED, saved.getEventType());
        assertEquals(clock.instant(), saved.getCreatedAt().toInstant());
    }

    @Test
    @DisplayName("写入失败只告警、不抛出：事件流水是旁路，不该让设备登记失败")
    void 写入失败不抛出() {
        when(mapper.insert(any(DeviceEvent.class))).thenThrow(new RuntimeException("表不存在"));

        assertDoesNotThrow(() -> service.record(DEVICE_ID, DeviceEventType.ONLINE, "上线"));
    }

    // ————————————————— 分页查询 —————————————————

    @Test
    @DisplayName("不传分页参数 → 用配置的默认值（page=1，size=20）")
    void 分页默认值() {
        givenPage(2);

        PageResult<DeviceEventResponse> result = service.page(DEVICE_ID, null, null);

        assertEquals(1, result.getPage());
        assertEquals(20, result.getSize());
        assertEquals(1, currentPageOfLastQuery());
        assertEquals(20, pageSizeOfLastQuery());
    }

    @Test
    @DisplayName("size 超过上限 → 静默裁到 event-max-page-size（100）")
    void 每页上限裁剪() {
        givenPage(0);

        PageResult<DeviceEventResponse> result = service.page(DEVICE_ID, 1, 5000);

        assertEquals(100, result.getSize());
        assertEquals(100, pageSizeOfLastQuery());
    }

    @Test
    @DisplayName("page=0 → 40001")
    void 页码非法返回_40001() {
        BizException ex = assertThrows(BizException.class, () -> service.page(DEVICE_ID, 0, 20));
        assertEquals(40001, ex.code());
        verify(mapper, never()).selectPage(ArgumentMatchers.<Page<DeviceEvent>>any(),
                ArgumentMatchers.<LambdaQueryWrapper<DeviceEvent>>any());
    }

    @Test
    @DisplayName("size=0 → 40001")
    void 每页条数非法返回_40001() {
        BizException ex = assertThrows(BizException.class, () -> service.page(DEVICE_ID, 1, 0));
        assertEquals(40001, ex.code());
    }

    @Test
    @DisplayName("分页结果：total 与列表透传，page/size 用安全值回显")
    void 分页结果透传() {
        givenPage(3);

        PageResult<DeviceEventResponse> result = service.page(DEVICE_ID, 2, 10);

        assertEquals(2, result.getPage());
        assertEquals(10, result.getSize());
        // givenPage(3) 打桩出 3 条记录、total=4，这里验证的是二者被原样透传
        assertEquals(4L, result.getTotal());
        assertEquals(3, result.getList().size());
        assertEquals(1L, result.getList().get(0).eventId());
    }

    // ————————————————— 辅助 —————————————————

    /** 打桩 selectPage：返回 {@code count} 条记录，total 固定为 count + 1。 */
    private void givenPage(int count) {
        when(mapper.selectPage(ArgumentMatchers.<Page<DeviceEvent>>any(),
                ArgumentMatchers.<LambdaQueryWrapper<DeviceEvent>>any()))
                .thenAnswer(invocation -> {
                    Page<DeviceEvent> page = invocation.getArgument(0);
                    List<DeviceEvent> records = new java.util.ArrayList<>();
                    for (int i = 1; i <= count; i++) {
                        DeviceEvent event = new DeviceEvent();
                        event.setId((long) i);
                        event.setDeviceId(DEVICE_ID);
                        event.setEventType(DeviceEventType.ONLINE);
                        records.add(event);
                    }
                    page.setRecords(records);
                    page.setTotal(count + 1L);
                    return page;
                });
    }

    @SuppressWarnings("unchecked")
    private Page<DeviceEvent> lastQueryPage() {
        ArgumentCaptor<Page<DeviceEvent>> captor = ArgumentCaptor.forClass(Page.class);
        verify(mapper).selectPage(captor.capture(),
                ArgumentMatchers.<LambdaQueryWrapper<DeviceEvent>>any());
        return captor.getValue();
    }

    private long currentPageOfLastQuery() {
        return lastQueryPage().getCurrent();
    }

    private long pageSizeOfLastQuery() {
        return lastQueryPage().getSize();
    }
}
