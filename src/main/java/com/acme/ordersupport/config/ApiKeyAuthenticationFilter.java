package com.acme.ordersupport.config;

import com.acme.ordersupport.order.repo.CustomerRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the {@code X-API-Key} header to a {@link CustomerPrincipal}.
 *
 * <p>A real deployment would put OIDC or a session cookie here. What matters for
 * this service is only that identity is settled at the edge, before any request
 * reaches the agent, and that the agent has no way to influence it.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final CustomerRepository customers;

    public ApiKeyAuthenticationFilter(CustomerRepository customers) {
        this.customers = customers;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String apiKey = request.getHeader(HEADER);
        if (StringUtils.hasText(apiKey) && SecurityContextHolder.getContext().getAuthentication() == null) {
            customers.findByApiKeyHash(sha256Hex(apiKey)).ifPresent(principal -> {
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        chain.doFilter(request, response);
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every JRE", ex);
        }
    }
}
