package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.domain.IdempotencyRecord;
import com.medbox.server.dto.auth.TokenResponse;
import com.medbox.server.mapper.IdempotencyRecordMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 幂等去重（Mockito 打桩 Mapper，不启容器、不连库）。 */
class IdempotencyServiceTest {

    private static final String REQUEST_ID = "8f2c1a9e3b7d4c1a9f0e2d5b6a7c8f10";

    private final IdempotencyRecordMapper mapper = mock(IdempotencyRecordMapper.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final IdempotencyService service = new IdempotencyService(mapper, objectMapper, 24);

    @Test
    void 命中记录时回放首次响应() throws Exception {
        TokenResponse first = new TokenResponse("access-1", "refresh-1", "u-1001", "GUARDIAN", 7200L);
        String hash = service.fingerprint("13800001234", "GUARDIAN", null, "李四");
        when(mapper.selectOne(any())).thenReturn(record(hash, objectMapper.writeValueAsString(first), 1));

        Optional<TokenResponse> replayed =
                service.replay(IdempotencyService.ENDPOINT_AUTH_REGISTER, REQUEST_ID, hash);

        assertTrue(replayed.isPresent());
        assertEquals("access-1", replayed.get().token());
        assertEquals("u-1001", replayed.get().userId());
    }

    @Test
    void 同一个号换了请求内容报_40001() {
        when(mapper.selectOne(any())).thenReturn(record("other-hash", "{}", 1));

        BizException ex = assertThrows(BizException.class, () -> service.replay(
                IdempotencyService.ENDPOINT_AUTH_REGISTER, REQUEST_ID, "this-hash"));
        assertEquals(ErrorCode.BAD_REQUEST.code(), ex.code());
    }

    @Test
    void 没带_X_Request_Id_时直接跳过_不查库() {
        assertTrue(service.replay(IdempotencyService.ENDPOINT_AUTH_REGISTER, null, "hash").isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    void 过期记录不再回放_并删掉让号可重用() {
        when(mapper.selectOne(any())).thenReturn(record("hash", "{}", -1));

        Optional<TokenResponse> replayed =
                service.replay(IdempotencyService.ENDPOINT_AUTH_REGISTER, REQUEST_ID, "hash");

        assertTrue(replayed.isEmpty());
        verify(mapper).deleteById(1L);
    }

    @Test
    void 落库时的唯一键冲突被吞掉_首个响应才是权威结果() {
        doThrow(new RuntimeException("duplicate key")).when(mapper).insert(any(IdempotencyRecord.class));

        assertDoesNotThrow(() -> service.save(IdempotencyService.ENDPOINT_AUTH_REGISTER, REQUEST_ID, "hash",
                new TokenResponse("access-1", "refresh-1", "u-1001", "GUARDIAN", 7200L)));
    }

    @Test
    void 指纹只覆盖身份字段_密码不同也算同一次提交() {
        String first = service.fingerprint("13800001234", "GUARDIAN", null, "李四");
        String second = service.fingerprint("13800001234", "GUARDIAN", null, "李四");

        assertEquals(first, second);
        assertEquals(64, first.length());
    }

    private static IdempotencyRecord record(String requestHash, String snapshot, int expiresInHours) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setId(1L);
        record.setRequestId(REQUEST_ID);
        record.setEndpoint(IdempotencyService.ENDPOINT_AUTH_REGISTER);
        record.setRequestHash(requestHash);
        record.setResponseSnapshot(snapshot);
        record.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(expiresInHours));
        return record;
    }
}
