package com.mewcode;

import com.mewcode.config.MewCodeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

@SpringBootApplication
@EnableConfigurationProperties(MewCodeProperties.class)
public class MewCodeApplication {

    public static void main(String[] args) {
        var app = new SpringApplication(MewCodeApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }

    @Bean
    public HttpClient httpClient(MewCodeProperties props) {
        return HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(props.getHttp().getTimeoutSeconds()))
            .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
