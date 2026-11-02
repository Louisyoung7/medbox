package com.medbox.server.common.ratelimit;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.config.RateLimitProperties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RateLimitServiceTest {

    private RateLimitService service(RateLimitProperties props, AtomicLong clock) {
        return new RateLimitService(props, clock::get);
    }

    private RateLimitProperties props() {
        RateLimitProperties p = new RateLimitProperties();
        p.setEnabled(true);
        p.getLogin().setLimit(3);
        p.getLogin().setWindowSeconds(60);
        return p;
    }

    @Test
    void 未超限不抛() {
        var clock = new AtomicLong(1000);
        var svc = service(props(), clock);
        for (int i = 0; i < 3; i++) {
            svc.tryAcquire(RateLimitPolicy.LOGIN, "1.2.3.4");
        }
    }

    @Test
    void 超限抛42901() {
        var clock = new AtomicLong(1000);
        var svc = service(props(), clock);
        String ip = "1.2.3.4";
        svc.tryAcquire(RateLimitPolicy.LOGIN, ip);
        svc.tryAcquire(RateLimitPolicy.LOGIN, ip);
        svc.tryAcquire(RateLimitPolicy.LOGIN, ip);
        BizException ex = assertThrows(BizException.class, () -> svc.tryAcquire(RateLimitPolicy.LOGIN, ip));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS, ex.errorCode());
    }

    @Test
    void 不同key独立计数() {
        var clock = new AtomicLong(1000);
        var svc = service(props(), clock);
        for (int i = 0; i < 3; i++) {
            svc.tryAcquire(RateLimitPolicy.LOGIN, "a");
        }
        // b 仍可用满 3 次
        for (int i = 0; i < 3; i++) {
            svc.tryAcquire(RateLimitPolicy.LOGIN, "b");
        }
    }

    @Test
    void 不同维度独立() {
        var clock = new AtomicLong(1000);
        var svc = service(props(), clock);
        for (int i = 0; i < 3; i++) {
            svc.tryAcquire(RateLimitPolicy.LOGIN, "same");
        }
        // 同样的 key 但不同维度仍可正常获取
        svc.tryAcquire(RateLimitPolicy.DEVICE_COMMAND, "same");
    }

    @Test
    void 窗口滑动后恢复() {
        var clock = new AtomicLong(1000);
        var svc = service(props(), clock);
        String ip = "1.2.3.4";
        for (int i = 0; i < 3; i++) {
            svc.tryAcquire(RateLimitPolicy.LOGIN, ip);
        }
        // 推进超过窗口（60s）
        clock.addAndGet(61);
        // 新窗口重新计数，3 次仍可用
        for (int i = 0; i < 3; i++) {
            svc.tryAcquire(RateLimitPolicy.LOGIN, ip);
        }
    }

    @Test
    void 总开关关闭时跳过() {
        RateLimitProperties p = props();
        p.setEnabled(false);
        var clock = new AtomicLong(1000);
        var svc = service(p, clock);
        for (int i = 0; i < 100; i++) {
            svc.tryAcquire(RateLimitPolicy.LOGIN, "1.2.3.4");
        }
    }

    @Test
    void 单维度关闭时跳过() {
        RateLimitProperties p = props();
        p.getLogin().setEnabled(false);
        var clock = new AtomicLong(1000);
        var svc = service(p, clock);
        for (int i = 0; i < 100; i++) {
            svc.tryAcquire(RateLimitPolicy.LOGIN, "1.2.3.4");
        }
    }
}
