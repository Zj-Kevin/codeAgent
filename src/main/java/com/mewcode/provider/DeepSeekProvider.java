package com.mewcode.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.config.MewCodeProperties;
import com.mewcode.prompt.PromptBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;

@Component
@ConditionalOnProperty(name = "mewcode.provider.protocol", havingValue = "deepseek")
public class DeepSeekProvider extends OpenAICompatibleProvider {

    public DeepSeekProvider(MewCodeProperties props, HttpClient httpClient,
                            ObjectMapper json, PromptBuilder promptBuilder) {
        super(props, httpClient, json, promptBuilder);
    }

    @Override
    protected String defaultBaseUrl() { return "https://api.deepseek.com"; }
}
