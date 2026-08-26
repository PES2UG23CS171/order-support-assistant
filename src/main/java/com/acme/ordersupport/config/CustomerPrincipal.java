package com.acme.ordersupport.config;

import java.util.UUID;

/** The authenticated caller. Established from the API key before any agent code runs. */
public record CustomerPrincipal(UUID customerId, String email, String fullName) {
}
