package com.acme.ordersupport.web.dto;

import jakarta.validation.constraints.Size;

public record ReasonRequest(@Size(max = 500, message = "reason must be 500 characters or fewer")
                            String reason) {

    public String reasonOr(String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }
}
