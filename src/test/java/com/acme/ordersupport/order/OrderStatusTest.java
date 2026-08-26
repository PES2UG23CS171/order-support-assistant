package com.acme.ordersupport.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ordersupport.order.model.OrderStatus;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class OrderStatusTest {

    @Test
    void onlyPreShipmentStatusesAreCancellable() {
        assertThat(EnumSet.allOf(OrderStatus.class).stream().filter(OrderStatus::cancellable).toList())
                .containsExactlyInAnyOrder(OrderStatus.CREATED, OrderStatus.PAID, OrderStatus.PROCESSING);
    }

    @Test
    void onlyANewOrderHasNoCapturedPayment() {
        assertThat(EnumSet.allOf(OrderStatus.class).stream()
                .filter(status -> !status.paymentCaptured()).toList())
                .containsExactly(OrderStatus.CREATED);
    }
}
