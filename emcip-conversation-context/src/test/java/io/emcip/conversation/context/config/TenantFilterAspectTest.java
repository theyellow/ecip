package io.emcip.conversation.context.config;

import static org.mockito.Mockito.*;

import io.emcip.common.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantFilterAspectTest {

    @Mock private EntityManager entityManager;
    @Mock private Session session;
    @Mock private Filter hibernateFilter;

    @InjectMocks private TenantFilterAspect aspect;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void enablesFilterWhenTenantIsSet() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId.toString());

        when(entityManager.unwrap(Session.class)).thenReturn(session);
        when(session.enableFilter("tenantFilter")).thenReturn(hibernateFilter);
        when(hibernateFilter.setParameter("tenantId", tenantId)).thenReturn(hibernateFilter);

        aspect.applyTenantFilter();

        verify(session).enableFilter("tenantFilter");
        verify(hibernateFilter).setParameter("tenantId", tenantId);
    }

    @Test
    void doesNotEnableFilterWhenTenantIsAbsent() {
        aspect.applyTenantFilter();
        verifyNoInteractions(entityManager);
    }
}
