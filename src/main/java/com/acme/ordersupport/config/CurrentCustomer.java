package com.acme.ordersupport.config;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for "who is asking".
 *
 * <p>Note what is absent: no method here takes a customer id as an argument. The
 * identity is read from the authenticated security context and nowhere else, which
 * is why no tool exposed to the model accepts a customer id parameter -- there is
 * no channel through which a model-generated value could become the acting
 * customer.
 */
@Component
public class CurrentCustomer {

    public Optional<CustomerPrincipal> principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return authentication.getPrincipal() instanceof CustomerPrincipal p ? Optional.of(p) : Optional.empty();
    }

    public CustomerPrincipal require() {
        return principal().orElseThrow(
                () -> new IllegalStateException("No authenticated customer in the security context"));
    }

    public UUID requireId() {
        return require().customerId();
    }
}
