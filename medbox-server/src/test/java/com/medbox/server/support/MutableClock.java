package com.medbox.server.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可手动推进的时钟：给"时间窗口"类单测用（补推窗口、空闲超时、令牌过期等）。
 *
 * <p>比 {@code Clock.fixed} 强在能推进 —— 窗口过期的用例必须能"跳过 10 分钟"，
 * 真等 10 分钟的测试没人愿意跑。
 */
public final class MutableClock extends Clock {

    private volatile long millis;

    private MutableClock(long millis) {
        this.millis = millis;
    }

    public static MutableClock at(long epochMillis) {
        return new MutableClock(epochMillis);
    }

    /** 推进（正数向前）；返回推进后的时间，便于链式断言。 */
    public long advanceMillis(long deltaMillis) {
        millis += deltaMillis;
        return millis;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis);
    }

    @Override
    public long millis() {
        return millis;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
