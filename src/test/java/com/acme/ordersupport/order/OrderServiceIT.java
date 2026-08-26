package com.acme.ordersupport.order;

import static com.acme.ordersupport.testsupport.Fixtures.ADA;
import static com.acme.ordersupport.testsupport.Fixtures.BLAKE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ordersupport.order.error.ActionNotAllowedException;
import com.acme.ordersupport.order.error.ConcurrentUpdateException;
import com.acme.ordersupport.order.error.OrderNotFoundException;
import com.acme.ordersupport.order.model.CancellationResult;
import com.acme.ordersupport.order.model.OrderStatus;
import com.acme.ordersupport.order.model.RefundStatus;
import com.acme.ordersupport.order.model.RefundView;
import com.acme.ordersupport.testsupport.DatabaseFixtures;
import com.acme.ordersupport.testsupport.IntegrationTest;
import com.acme.ordersupport.testsupport.PostgresSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The rules again, this time against a real database with the demo fixtures. */
@IntegrationTest
class OrderServiceIT extends PostgresSupport {

    @Autowired
    private OrderService orderService;

    @Autowired
    private RefundService refundService;

    @Autowired
    private DatabaseFixtures fixtures;

    @BeforeEach
    void reset() {
        fixtures.reset();
    }

    @Test
    void anUnshippedOrderCancelsAndOpensAFullRefund() {
        CancellationResult result = orderService.cancel(ADA, "ORD-1001", "ordered by mistake");

        assertThat(result.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(fixtures.statusOf("ORD-1001")).isEqualTo("CANCELLED");

        List<RefundView> refunds = refundService.refundsForOrder(ADA, "ORD-1001");
        assertThat(refunds).singleElement().satisfies(refund -> {
            assertThat(refund.status()).isEqualTo(RefundStatus.PENDING);
            assertThat(refund.amount()).isEqualByComparingTo("249.00");
            assertThat(refund.method()).isEqualTo("ORIGINAL_PAYMENT_METHOD");
            assertThat(refund.expectedSettlementDate()).isNotNull();
        });
    }

    @Test
    void aShippedOrderIsRefusedAndLeftAlone() {
        assertThatThrownBy(() -> orderService.cancel(ADA, "ORD-1002", "too late"))
                .isInstanceOf(ActionNotAllowedException.class);

        assertThat(fixtures.statusOf("ORD-1002")).isEqualTo("SHIPPED");
        assertThat(fixtures.countRefundsFor("ORD-1002")).isZero();
    }

    @Test
    void oneCustomerCannotSeeOrTouchAnothersOrder() {
        assertThatThrownBy(() -> orderService.lookup(ADA, "ORD-2001"))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> orderService.cancel(ADA, "ORD-2001", "not mine"))
                .isInstanceOf(OrderNotFoundException.class);

        assertThat(fixtures.statusOf("ORD-2001")).isEqualTo("PROCESSING");
        // Blake, who does own it, is unaffected by any of that.
        assertThat(orderService.lookup(BLAKE, "ORD-2001").orderNumber()).isEqualTo("ORD-2001");
    }

    @Test
    void listingReturnsOnlyTheCallersOrders() {
        assertThat(orderService.listOrders(ADA)).extracting("orderNumber")
                .containsExactlyInAnyOrder("ORD-1001", "ORD-1002", "ORD-1003", "ORD-1004", "ORD-1005",
                        "ORD-1006", "ORD-1007");
        assertThat(orderService.listOrders(BLAKE)).extracting("orderNumber").containsExactly("ORD-2001");
    }

    @Test
    void anOrderCarriesItsLinesAndShipment() {
        var order = orderService.lookup(ADA, "ORD-1002");

        assertThat(order.lines()).singleElement()
                .satisfies(line -> assertThat(line.sku()).isEqualTo("MON-ARM-02"));
        assertThat(order.shipmentOptional()).hasValueSatisfying(shipment -> {
            assertThat(shipment.carrier()).isEqualTo("UPS");
            assertThat(shipment.trackingNumber()).isEqualTo("1Z999AA10123456784");
        });
    }

    @Test
    void twoSimultaneousCancellationsProduceOneCancellationAndOneConflict() throws Exception {
        // The optimistic lock earns its keep here: without it both transactions
        // would write CANCELLED and both would open a refund for the same order.
        int attempts = 6;
        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        List<Future<String>> results = new ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                startLine.await();
                try {
                    orderService.cancel(ADA, "ORD-1001", "race");
                    return "cancelled";
                }
                catch (ConcurrentUpdateException | ActionNotAllowedException ex) {
                    return "rejected";
                }
            }));
        }
        startLine.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<String> outcomes = new ArrayList<>();
        for (Future<String> result : results) {
            outcomes.add(result.get());
        }

        assertThat(outcomes).filteredOn("cancelled"::equals).hasSize(1);
        assertThat(fixtures.statusOf("ORD-1001")).isEqualTo("CANCELLED");
        assertThat(fixtures.countRefundsFor("ORD-1001")).isEqualTo(1);
    }
}
