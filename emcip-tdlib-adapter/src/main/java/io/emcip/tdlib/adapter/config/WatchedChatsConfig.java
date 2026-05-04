package io.emcip.tdlib.adapter.config;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WatchedChatsConfig {

    @Bean
    public ConcurrentMap<UUID, Set<Long>> watchedChatIds() {
        return new ConcurrentHashMap<>();
    }
}
