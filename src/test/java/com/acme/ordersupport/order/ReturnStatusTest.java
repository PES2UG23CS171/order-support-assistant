package com.acme.ordersupport.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ordersupport.order.model.ReturnStatus;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ReturnStatusTest {

    @Test
    void aReturnIsOpenFromRequestUntilItIsResolved() {
        // This set is what blocks a second RMA on the same order, so it is worth
        // pinning: adding a status without deciding which side it falls on would
        // silently let a customer open two returns at once.
        assertThat(EnumSet.allOf(ReturnStatus.class).stream().filter(ReturnStatus::open).toList())
                .containsExactlyInAnyOrder(ReturnStatus.REQUESTED, ReturnStatus.LABEL_SENT,
                        ReturnStatus.IN_TRANSIT, ReturnStatus.RECEIVED, ReturnStatus.INSPECTED);
    }

    @Test
    void resolvedReturnsAreClosed() {
        assertThat(EnumSet.allOf(ReturnStatus.class).stream().filter(status -> !status.open()).toList())
                .containsExactlyInAnyOrder(ReturnStatus.COMPLETED, ReturnStatus.REJECTED,
                        ReturnStatus.EXPIRED);
    }
}
