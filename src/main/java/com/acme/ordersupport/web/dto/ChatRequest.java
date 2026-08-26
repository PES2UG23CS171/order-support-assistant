package com.acme.ordersupport.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * @param conversationId omit to start a new conversation; supply to continue one
 *                       you own. Ownership is verified server-side.
 */
public record ChatRequest(UUID conversationId,
                          @NotBlank(message = "message must not be blank")
                          @Size(max = 4000, message = "message must be 4000 characters or fewer")
                          String message) {
}
