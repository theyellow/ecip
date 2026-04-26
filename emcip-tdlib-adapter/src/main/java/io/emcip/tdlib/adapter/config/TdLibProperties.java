package io.emcip.tdlib.adapter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tdlib")
public record TdLibProperties(
        String baseDirectory,
        boolean useFileDatabase,
        boolean useChatInfoDatabase,
        boolean useMessageDatabase,
        boolean useSecretChats,
        int logVerbosityLevel) {
    public TdLibProperties {
        baseDirectory = baseDirectory != null ? baseDirectory : "tdlib-db";
        logVerbosityLevel = logVerbosityLevel > 0 ? logVerbosityLevel : 1;
    }
}
