package com.acme.ordersupport.order.model;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderLineView(UUID id, String sku, String description, String category,
                            int quantity, BigDecimal unitPrice, boolean returnable) {

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
