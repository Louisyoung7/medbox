package com.medbox.server.common.ratelimit;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.config.RateLimitProperties;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 内存固定窗口计数器限流（feat/ai-limit）。
 *
 * <p>单实例、局域网部署下足够：与 {@code WsEventBuffer} / {@code PendingAckRegistry} 同一内存口径，
 * 不引入 Redis 等外部依赖。重启即丢、多实例各计各的（本期可接受）。
 *
 * <p>本类不是 Spring 组件（不挂 {@code @Service}）：{@code RateLimitInterceptor} 直接持有其实例，
 * 以避免在 {@code @WebMvcTest} 等切片测试里因 {@code @Service} 不被扫描而缺 bean。
 * 单元测试直接构造本类即可。
 *
 * <p>超限抛 {@link BizException#of(ErrorCode#TOO_MANY_REQUESTS)}，由
 * {@code GlobalExceptionHandler} 统一输出 {@code code=42901} + HTTP 429。
 */
public class RateLimitService {

    private final Map<String, Window> counters = new ConcurrentHashMap<>();
    private final RateLimitProperties props;
    private final LongSupplier nowSeconds;

    public RateLimitService(RateLimitProperties props) {
        this(props, () -> Instant.now().getEpochSecond());
    }

    /** 测试用：注入可控时钟，便于确定性验证窗口滑动。 */
    RateLimitService(RateLimitProperties props, LongSupplier nowSeconds) {
        this.props = props;
        this.nowSeconds = nowSeconds;
    }

    /**
     * 尝试获取一次配额；超限抛 {@code 42901}。
     *
     * @param policy 命中的限流策略（决定维度与默认阈值）
     * @param key    限流 key（IP / userId / deviceId 等）
     */
    public void tryAcquire(RateLimitPolicy policy, String key) {
        if (!props.isEnabled()) {
            return;
        }
        RateLimitProperties.RateLimitPolicyConfig cfg = props.forPolicy(policy);
        if (cfg == null || !cfg.isEnabled()) {
            return;
        }
        int limit = cfg.getLimit() > 0 ? cfg.getLimit() : policy.defaultLimit();
        long windowSeconds = cfg.getWindowSeconds() > 0 ? cfg.getWindowSeconds() : policy.defaultWindowSeconds();

        String mapKey = policy.dimension() + ":" + key;
        long now = nowSeconds.getAsLong();
        Window window = counters.compute(mapKey, (k, existing) -> {
            if (existing == null || now - existing.windowStart >= windowSeconds) {
                return new Window(now, 1L);
            }
            existing.count.incrementAndGet();
            return existing;
        });
        if (window.count.get() > limit) {
            throw BizException.of(ErrorCode.TOO_MANY_REQUESTS);
        }
    }

    /** 固定窗口：窗口起点（epoch 秒）+ 计数。 */
    private static final class Window {
        final long windowStart;
        final AtomicLong count;

        Window(long windowStart, long count) {
            this.windowStart = windowStart;
            this.count = new AtomicLong(count);
        }
    }
}
