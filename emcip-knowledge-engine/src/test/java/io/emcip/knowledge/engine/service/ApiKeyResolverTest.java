package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.entity.VendorApiKey;
import io.emcip.knowledge.engine.repository.VendorApiKeyRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApiKeyResolverTest {

    @Mock private VendorApiKeyRepository repo;

    private ApiKeyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ApiKeyResolver(repo);
    }

    @Test
    void returnsTenantSpecificKey_whenPresent() {
        UUID tenantId = UUID.randomUUID();
        VendorApiKey key = new VendorApiKey();
        key.setApiKey("tenant-key");
        when(repo.findByVendorIdAndTenantId("exa", tenantId)).thenReturn(Optional.of(key));

        Optional<String> result = resolver.resolve("exa", tenantId);
        assertThat(result).contains("tenant-key");
    }

    @Test
    void fallsBackToGlobal_whenNoTenantKey() {
        UUID tenantId = UUID.randomUUID();
        VendorApiKey global = new VendorApiKey();
        global.setApiKey("global-key");
        when(repo.findByVendorIdAndTenantId("exa", tenantId)).thenReturn(Optional.empty());
        when(repo.findByVendorIdAndTenantIdIsNull("exa")).thenReturn(Optional.of(global));

        Optional<String> result = resolver.resolve("exa", tenantId);
        assertThat(result).contains("global-key");
    }

    @Test
    void returnsEmpty_whenNoKeyAtAll() {
        UUID tenantId = UUID.randomUUID();
        when(repo.findByVendorIdAndTenantId("exa", tenantId)).thenReturn(Optional.empty());
        when(repo.findByVendorIdAndTenantIdIsNull("exa")).thenReturn(Optional.empty());

        Optional<String> result = resolver.resolve("exa", tenantId);
        assertThat(result).isEmpty();
    }
}
