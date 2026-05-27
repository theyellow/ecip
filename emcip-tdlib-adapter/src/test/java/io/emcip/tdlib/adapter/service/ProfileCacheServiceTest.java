package io.emcip.tdlib.adapter.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfileCacheServiceTest {

    private ProfileCacheService cache;

    @BeforeEach
    void setUp() {
        cache = new ProfileCacheService();
    }

    @Test
    void putAndGetUser() {
        cache.putUser(123L, "John Doe", "johndoe");
        assertThat(cache.getUserDisplayName(123L)).isEqualTo("John Doe");
        assertThat(cache.getUserUsername(123L)).isEqualTo("johndoe");
    }

    @Test
    void getUser_miss_returnsNull() {
        assertThat(cache.getUserDisplayName(999L)).isNull();
        assertThat(cache.getUserUsername(999L)).isNull();
    }

    @Test
    void putAndGetChat() {
        cache.putChat(-1001234L, "Crypto Watch");
        assertThat(cache.getChatTitle(-1001234L)).isEqualTo("Crypto Watch");
    }

    @Test
    void getChat_miss_returnsNull() {
        assertThat(cache.getChatTitle(999L)).isNull();
    }

    @Test
    void putUser_updatesExisting() {
        cache.putUser(123L, "John", "john");
        cache.putUser(123L, "John Doe", "johndoe");
        assertThat(cache.getUserDisplayName(123L)).isEqualTo("John Doe");
    }

    @Test
    void putChat_updatesExisting() {
        cache.putChat(-100L, "Old Title");
        cache.putChat(-100L, "New Title");
        assertThat(cache.getChatTitle(-100L)).isEqualTo("New Title");
    }
}
