package com.acme.ordersupport.order.model;

import java.math.BigDecimal;

public record ReturnResult(String orderNumber, String rmaNumber, ReturnStatus status,
                           BigDecimal estimatedRefund, BigDecimal deductedFees, String message) {
}
