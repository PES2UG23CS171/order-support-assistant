package com.acme.ordersupport.config;

import static com.acme.ordersupport.testsupport.Fixtures.ADA;
import static com.acme.ordersupport.testsupport.Fixtures.ADA_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.ordersupport.order.repo.CustomerRepository;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationFilterTest {

    /** The value seeded in V900__demo_data.sql, so the fixture and the code agree. */
    private static final String ADA_KEY_HASH =
            "83514f5aded0713a24ca7de3f41e7c5a3518a1ea48253300b43dc22e9415547c";

    @Mock
    private CustomerRepository customers;

    @Mock
    private FilterChain chain;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void hashesTheKeyRatherThanComparingItInTheClear() {
        assertThat(ApiKeyAuthenticationFilter.sha256Hex(ADA_KEY)).isEqualTo(ADA_KEY_HASH);
    }

    @Test
    void aValidKeyBecomesAnAuthenticatedCustomerPrincipal() throws Exception {
        when(customers.findByApiKeyHash(ADA_KEY_HASH)).thenReturn(
                Optional.of(new CustomerPrincipal(ADA, "ada@example.com", "Ada Lovelace")));
        var request = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthenticationFilter.HEADER, ADA_KEY);

        new ApiKeyAuthenticationFilter(customers)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isInstanceOf(CustomerPrincipal.class)
                .extracting("customerId").isEqualTo(ADA);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void anUnknownKeyLeavesTheContextEmptyForTheEntryPointToReject() throws Exception {
        when(customers.findByApiKeyHash(anyString())).thenReturn(Optional.empty());
        var request = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthenticationFilter.HEADER, "not-a-real-key");

        new ApiKeyAuthenticationFilter(customers)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(any(), any());
    }

    @Test
    void noHeaderMeansNoDatabaseLookup() throws Exception {
        new ApiKeyAuthenticationFilter(customers)
                .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        verifyNoInteractions(customers);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
