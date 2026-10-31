package com.medbox.server.controller;
import com.medbox.server.dto.R;
import com.medbox.server.dto.ai.InsightRequest;
import com.medbox.server.dto.ai.InsightResponse;
import com.medbox.server.dto.ai.InsightScheduleRequest;
import com.medbox.server.dto.ai.InsightScheduleResponse;
import com.medbox.server.dto.ai.LlmConfigRequest;
import com.medbox.server.dto.ai.LlmConfigResponse;
import com.medbox.server.dto.ai.LlmConfigTestResponse;
import com.medbox.server.service.AiInsightService;
import com.medbox.server.service.LlmConfigService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/ai")

public class AiController{
    private final LlmConfigService configService;
    private final AiInsightService insightService;

    public AiController(LlmConfigService configService, AiInsightService insightService) {
        this.configService = configService;
        this.insightService = insightService;
    }

    @PutMapping("/config")

    public R<LlmConfigResponse> save (@RequestParam("elderId")String elderId,@RequestBody LlmConfigRequest request){
        return R.ok(configService.save(elderId, request));
    }
    @GetMapping("/config")
    public R<LlmConfigResponse> get(@RequestParam("elderId") String elderId) {
        return R.ok(configService.get(elderId));
    }

    @PostMapping("/config/test")
    public R<LlmConfigTestResponse> test(@RequestParam("elderId") String elderId) {
        return R.ok(configService.test(elderId));
    }

    @DeleteMapping("/config")
    public R<Void> delete(@RequestParam("elderId") String elderId) {
        configService.delete(elderId);
        return R.ok();
    }

    @PostMapping("/insight")
    public R<InsightResponse> insight(@RequestBody InsightRequest request) {
        return R.ok(insightService.insight(request));
    }

    /** SSE：produces 必须显式声明，否则 SseEmitter 会被当成普通对象序列化。 */
    @PostMapping(value = "/insight/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody InsightRequest request) {
        return insightService.stream(request);
    }

    @GetMapping("/insight/schedule")
    public R<InsightScheduleResponse> schedule(@RequestParam("elderId") String elderId) {
        return R.ok(configService.schedule(elderId));
    }

    @PutMapping("/insight/schedule")
    public R<InsightScheduleResponse> updateSchedule(@RequestParam("elderId") String elderId,
                                                     @RequestBody InsightScheduleRequest request) {
        return R.ok(configService.updateSchedule(elderId, request));
    }
}