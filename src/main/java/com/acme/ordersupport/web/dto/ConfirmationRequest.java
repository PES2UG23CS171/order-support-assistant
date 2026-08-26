package com.acme.ordersupport.web.dto;

import com.acme.ordersupport.agent.confirm.ConfirmationDecision;
import jakarta.validation.constraints.NotNull;

public record ConfirmationRequest(@NotNull(message = "decision must be APPROVE or REJECT")
                                  ConfirmationDecision decision) {
}
