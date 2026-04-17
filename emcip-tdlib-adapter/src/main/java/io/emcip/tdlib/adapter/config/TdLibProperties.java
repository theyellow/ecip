package io.emcip.tdlib.adapter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tdlib")
public record TdLibProperties(
        int apiId,
        String apiHash,
        String phoneNumber,
        String databaseDirectory,
        String filesDirectory,
        boolean useFileDatabase,
        boolean useChatInfoDatabase,
        boolean useMessageDatabase,
        boolean useSecretChats,
        int logVerbosityLevel) {
    public TdLibProperties {
        databaseDirectory = databaseDirectory != null ? databaseDirectory : "tdlib-db";
        filesDirectory = filesDirectory != null ? filesDirectory : "tdlib-files";
        useFileDatabase = useFileDatabase;
        useChatInfoDatabase = useChatInfoDatabase;
        useMessageDatabase = useMessageDatabase;
        useSecretChats = useSecretChats;
        logVerbosityLevel = logVerbosityLevel > 0 ? logVerbosityLevel : 1;
    }
}
