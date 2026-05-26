package io.emcip.tdlib.adapter.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class ProfileCacheService {

    private final Cache<Long, UserInfo> userProfiles =
            Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).maximumSize(50_000).build();

    private final Cache<Long, String> chatTitles =
            Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).maximumSize(10_000).build();

    public void putUser(long userId, String displayName, String username) {
        userProfiles.put(userId, new UserInfo(displayName, username));
    }

    public String getUserDisplayName(long userId) {
        UserInfo info = userProfiles.getIfPresent(userId);
        return info != null ? info.displayName() : null;
    }

    public String getUserUsername(long userId) {
        UserInfo info = userProfiles.getIfPresent(userId);
        return info != null ? info.username() : null;
    }

    public void putChat(long chatId, String title) {
        chatTitles.put(chatId, title);
    }

    public String getChatTitle(long chatId) {
        return chatTitles.getIfPresent(chatId);
    }

    private record UserInfo(String displayName, String username) {}
}
