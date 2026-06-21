package com.mewcode;

import org.springframework.boot.SpringApplication;

import java.util.Map;

public class App {
    public static void main(String[] args) {
        var app = new SpringApplication(MewCodeApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);

        // Support --config <path> by mapping to spring.config.location
        String configPath = extractConfigArg(args);
        if (configPath != null) {
            app.setDefaultProperties(Map.of(
                "spring.config.location", "file:" + configPath
            ));
        }

        // Legacy ~/.mewcode/config.yaml is no longer read automatically.
        // Migrate it to application.yml format (see docs).

        app.run(args);
    }

    private static String extractConfigArg(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--config".equals(args[i])) return args[i + 1];
        }
        return null;
    }
}
