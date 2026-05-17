package io.emcip.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantContextFilterTest {

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    private final TenantContextFilter filter = new TenantContextFilter();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void setsAndClearsTenantIdForRequest() throws Exception {
        when(request.getHeader(TenantContext.HEADER_NAME)).thenReturn("tenant-123");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        // Context must be cleared after filter runs
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void tenantIdAvailableDuringFilterChain() throws Exception {
        when(request.getHeader(TenantContext.HEADER_NAME)).thenReturn("tenant-abc");

        doAnswer(
                        invocation -> {
                            assertThat(TenantContext.getTenantId()).isEqualTo("tenant-abc");
                            return null;
                        })
                .when(filterChain)
                .doFilter(request, response);

        filter.doFilterInternal(request, response, filterChain);
    }

    @Test
    void noHeader_returns400() throws Exception {
        when(request.getHeader(TenantContext.HEADER_NAME)).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(response)
                .sendError(HttpServletResponse.SC_BAD_REQUEST, "X-Tenant-Id header is required");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void blankHeader_returns400() throws Exception {
        when(request.getHeader(TenantContext.HEADER_NAME)).thenReturn("   ");

        filter.doFilterInternal(request, response, filterChain);

        verify(response)
                .sendError(HttpServletResponse.SC_BAD_REQUEST, "X-Tenant-Id header is required");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void contextClearedEvenWhenFilterChainThrows() throws Exception {
        when(request.getHeader(TenantContext.HEADER_NAME)).thenReturn("tenant-xyz");
        doThrow(new RuntimeException("chain error")).when(filterChain).doFilter(request, response);

        try {
            filter.doFilterInternal(request, response, filterChain);
        } catch (RuntimeException ignored) {
        }

        assertThat(TenantContext.getTenantId()).isNull();
    }
}
