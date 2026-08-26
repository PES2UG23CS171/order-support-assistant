package com.acme.ordersupport.order.model;

import java.util.Optional;

public record CancellationResult(String orderNumber, OrderStatus status, RefundView refund, String message) {

    public Optional<RefundView> refundOptional() {
        return Optional.ofNullable(refund);
    }
}
