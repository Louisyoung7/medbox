package com.medbox.server.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 空闲连接回收（见文档 04 第 1 章"心跳…超时断开"）。
 *
 * <p>心跳与超时两个阈值：客户端每 {@code medbox.ws.heartbeat-seconds=30}s 发 {@code ping}，
 * 服务端取 3 个周期（默认 90s）作为僵死阈值 —— 容忍偶发丢包，也不会让死连接长期占着资源。
 *
 * <p><b>为什么自己扫描而不是依赖容器的 idle 配置</b>：容器层只能配"整个会话的空闲超时"，
 * 值随 Tomcat / Jetty 而变、且单测里根本跑不起来；放进应用里，阈值是 {@code application.yml} 的
 * 一项配置，测试可以直接调 {@link #reap()} 断言。
 */
public class WsIdleSessionReaper {

    private static final Logger log = LoggerFactory.getLogger(WsIdleSessionReaper.class);

    private final WsSessionManager sessions;
    private final WsProperties props;

    public WsIdleSessionReaper(WsSessionManager sessions, WsProperties props) {
        this.sessions = sessions;
        this.props = props;
    }

    /** 定时扫描；间隔与首次延迟都取 {@code medbox.ws.idle-scan-interval-ms}。 */
    @Scheduled(initialDelayString = "${medbox.ws.idle-scan-interval-ms:30000}",
               fixedDelayString = "${medbox.ws.idle-scan-interval-ms:30000}")
    public void reap() {
        int closed = sessions.closeIdle();
        if (closed > 0) {
            log.info("WS 空闲回收：关闭 {} 条连接（阈值 {}s）", closed, props.getIdleTimeoutSeconds());
        }
    }
}
