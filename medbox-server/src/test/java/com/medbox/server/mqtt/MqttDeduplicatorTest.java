package com.medbox.server.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medbox.server.domain.MqttMessage;
import com.medbox.server.mapper.MqttMessageMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

/**
 * 上行 {@code msgId} 去重（CI 可跑，Mapper 打桩）。
 *
 * <p>核心断言：<b>重复消息靠"唯一键冲突"被认出来，而不是靠先查后插</b>；
 * 这正是设备断线重连重发时"同一条只处理一次"的保证（见文档 03 第 1 章）。
 */
class MqttDeduplicatorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-11T02:00:00Z"), ZoneOffset.UTC);

    private MqttMessageMapper mapper;
    private MqttMessageDeduplicator deduplicator;

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(MqttMessageMapper.class);
        MqttProperties props = new MqttProperties();
        props.setDedupRetentionDays(7L);
        deduplicator = new MqttMessageDeduplicator(mapper, props, FIXED_CLOCK);
    }

    @Test
    @DisplayName("首次出现的 msgId 放行，并带上 7 天有效期")
    void claimSucceedsForNewMsgId() {
        when(mapper.insert(any(MqttMessage.class))).thenReturn(1);

        boolean claimed = deduplicator.claim("BOXA1001-0000000124", "BOXA1001", "medbox/pk/BOXA1001/up/telemetry");

        assertThat(claimed).isTrue();
        ArgumentCaptor<MqttMessage> captor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getMsgId()).isEqualTo("BOXA1001-0000000124");
        assertThat(captor.getValue().getDeviceId()).isEqualTo("BOXA1001");
        assertThat(captor.getValue().getReceivedAt()).isEqualTo(Instant.parse("2026-10-11T02:00:00Z").atOffset(ZoneOffset.UTC));
        assertThat(captor.getValue().getExpiresAt())
            .isEqualTo(Instant.parse("2026-10-18T02:00:00Z").atOffset(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("重复 msgId 走唯一键冲突分支，返回 false")
    void duplicateMsgIdRejected() {
        when(mapper.insert(any(MqttMessage.class)))
            .thenThrow(new DuplicateKeyException("duplicate key value violates unique constraint \"mqtt_message_msg_id_key\""));

        boolean first = deduplicator.claim("BOXA1001-0000000124", "BOXA1001", "medbox/pk/BOXA1001/up/event/dispense");
        boolean second = deduplicator.claim("BOXA1001-0000000124", "BOXA1001", "medbox/pk/BOXA1001/up/event/dispense");

        assertThat(first).isFalse();
        assertThat(second).isFalse();
    }

    @Test
    @DisplayName("同一 msgId 只占一次插入位：不是先 SELECT 再 INSERT")
    void usesSingleInsertRatherThanCheckThenInsert() {
        when(mapper.insert(any(MqttMessage.class))).thenReturn(1);

        deduplicator.claim("BOXA1001-0000000200", "BOXA1001", "medbox/pk/BOXA1001/up/ack");

        verify(mapper, times(1)).insert(any(MqttMessage.class));
        verify(mapper, never()).selectCount(any());
    }
}
