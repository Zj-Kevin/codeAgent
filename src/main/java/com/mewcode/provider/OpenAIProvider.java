package com.mewcode.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.config.MewCodeProperties;
import com.mewcode.prompt.PromptBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;

@Component
@ConditionalOnProperty(name = "mewcode.provider.protocol", havingValue = "openai")
public class OpenAIProvider extends OpenAICompatibleProvider {

    public OpenAIProvider(MewCodeProperties props, HttpClient httpClient,
                          ObjectMapper json, PromptBuilder promptBuilder) {
        super(props, httpClient, json, promptBuilder);
    }

    @Override
    protected String defaultBaseUrl() { return "https://api.openai.com"; }
}
