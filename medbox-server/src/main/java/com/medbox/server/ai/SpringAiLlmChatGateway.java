package com.medbox.server.ai;

import com.medbox.server.config.AiInsightProperties;
import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** 按老人配置动态构造 ChatClient（BYOK）：不做全局单例，不缓存 Key 明文。 */


@Component
public class SpringAiLlmChatGateway implements LlmChatGateway{
    private final AiInsightProperties props;

    public SpringAiLlmChatGateway(AiInsightProperties props){
        this.props=props;
    }

    @Override
    public String complete(LlmCredentials cred,String systemPrompt,String userPrompt){
        return client(cred).prompt().system(systemPrompt).user(userPrompt).call().content();
    }

    @Override 
    public Flux<String> stream(LlmCredentials cred,String systemPrompt,String userPrompt){
        return client(cred).prompt().system(systemPrompt).user(userPrompt).stream().content();
    }
    

    private ChatClient client(LlmCredentials cred){
        OpenAiChatOptions options =OpenAiChatOptions.builder()
                .apiKey(cred.apiKey())
                .baseUrl(cred.baseUrl())
                .model(cred.model())
                .temperature(0.3)
                .maxRetries(0)
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .build();
        return ChatClient.builder(OpenAiChatModel.builder().options(options).build()).build();
    }


}