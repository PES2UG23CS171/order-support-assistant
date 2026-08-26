package com.acme.ordersupport.order;

import static com.acme.ordersupport.testsupport.Fixtures.ADA;
import static com.acme.ordersupport.testsupport.Fixtures.BLAKE;
import static com.acme.ordersupport.testsupport.Fixtures.fixedClock;
import static com.acme.ordersupport.testsupport.Fixtures.orderRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.ordersupport.order.error.ActionNotAllowedException;
import com.acme.ordersupport.order.error.ConcurrentUpdateException;
import com.acme.ordersupport.order.error.OrderNotFoundException;
import com.acme.ordersupport.order.model.CancellationResult;
import com.acme.ordersupport.order.model.OrderStatus;
import com.acme.ordersupport.order.repo.OrderRepository;
import com.acme.ordersupport.order.repo.OrderRow;
import com.acme.ordersupport.order.repo.RefundRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orders;

    @Mock
    private RefundRepository refunds;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orders, refunds, fixedClock());
    }

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        void anotherCustomersOrderIsReportedAsNotFound() {
            when(orders.findByOrderNumber("ORD-2001"))
                    .thenReturn(Optional.of(orderRow("ORD-2001", BLAKE, OrderStatus.PROCESSING)));

            // Not "forbidden": a forbidden response would confirm the order number
            // is real, which turns the chat box into an order-number oracle.
            assertThatThrownBy(() -> service.lookup(ADA, "ORD-2001"))
                    .isInstanceOf(OrderNotFoundException.class)
                    .hasMessageContaining("No order ORD-2001 was found");
        }

        @Test
        void anUnknownOrderProducesTheSameErrorAsSomeoneElsesOrder() {
            when(orders.findByOrderNumber("ORD-9999")).thenReturn(Optional.empty());
            when(orders.findByOrderNumber("ORD-2001"))
                    .thenReturn(Optional.of(orderRow("ORD-2001", BLAKE, OrderStatus.PROCESSING)));

            String unknown = catchMessage(() -> service.lookup(ADA, "ORD-9999"));
            String notMine = catchMessage(() -> service.lookup(ADA, "ORD-2001"));

            assertThat(unknown.replace("ORD-9999", "X")).isEqualTo(notMine.replace("ORD-2001", "X"));
        }

        @Test
        void cancellingSomeoneElsesOrderTouchesNothing() {
            when(orders.findByOrderNumber("ORD-2001"))
                    .thenReturn(Optional.of(orderRow("ORD-2001", BLAKE, OrderStatus.PROCESSING)));

            assertThatThrownBy(() -> service.cancel(ADA, "ORD-2001", "why not"))
                    .isInstanceOf(OrderNotFoundException.class);

            verify(orders, never()).updateStatus(any(), any(), anyInt(), any());
            verifyNoInteractions(refunds);
        }
    }

    @Nested
    @DisplayName("cancellation eligibility")
    class CancellationEligibility {

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = {"CREATED", "PAID", "PROCESSING"})
        void unshippedOrdersAreCancellable(OrderStatus status) {
            when(orders.findByOrderNumber("ORD-1001"))
                    .thenReturn(Optional.of(orderRow("ORD-1001", ADA, status)));

            assertThat(service.assessCancellation(ADA, "ORD-1001").allowed()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class,
                names = {"SHIPPED", "IN_TRANSIT", "DELIVERED", "RETURN_REQUESTED", "RETURNED", "REFUNDED"})
        void shippedOrdersAreNot(OrderStatus status) {
            when(orders.findByOrderNumber("ORD-1002"))
                    .thenReturn(Optional.of(orderRow("ORD-1002", ADA, status)));

            var eligibility = service.assessCancellation(ADA, "ORD-1002");

            assertThat(eligibility.allowed()).isFalse();
            assertThat(eligibility.code()).isEqualTo("ORDER_ALREADY_SHIPPED");
            assertThat(eligibility.explanation()).contains("refuse the delivery");
        }

        @Test
        void anAlreadyCancelledOrderSaysSoRatherThanBlamingShipping() {
            when(orders.findByOrderNumber("ORD-1006"))
                    .thenReturn(Optional.of(orderRow("ORD-1006", ADA, OrderStatus.CANCELLED)));

            assertThat(service.assessCancellation(ADA, "ORD-1006").code()).isEqualTo("ALREADY_CANCELLED");
        }
    }

    @Nested
    @DisplayName("cancellation")
    class Cancellation {

        @Test
        void cancellingACapturedOrderOpensAFullRefund() {
            OrderRow order = orderRow("ORD-1001", ADA, OrderStatus.PROCESSING, new BigDecimal("249.00"), 3);
            when(orders.findByOrderNumber("ORD-1001")).thenReturn(Optional.of(order));
            when(orders.updateStatus(eq(order.id()), eq(OrderStatus.CANCELLED), eq(3), any(Instant.class)))
                    .thenReturn(1);

            CancellationResult result = service.cancel(ADA, "ORD-1001", "ordered by mistake");

            assertThat(result.status()).isEqualTo(OrderStatus.CANCELLED);
            verify(refunds).insert(any(UUID.class), eq(order.id()), eq("ORD-1001"), eq(null),
                    eq(com.acme.ordersupport.order.model.RefundStatus.PENDING), eq(new BigDecimal("249.00")),
                    eq("USD"), eq("ORIGINAL_PAYMENT_METHOD"), any(), any(), eq(null));
        }

        @Test
        void cancellingBeforePaymentWasCapturedOpensNoRefund() {
            OrderRow order = orderRow("ORD-1000", ADA, OrderStatus.CREATED, new BigDecimal("249.00"), 0);
            when(orders.findByOrderNumber("ORD-1000")).thenReturn(Optional.of(order));
            when(orders.updateStatus(any(), any(), anyInt(), any())).thenReturn(1);

            CancellationResult result = service.cancel(ADA, "ORD-1000", "changed my mind");

            assertThat(result.refundOptional()).isEmpty();
            assertThat(result.message()).contains("nothing to refund");
            verifyNoInteractions(refunds);
        }

        @Test
        void aShippedOrderIsRefusedByTheRuleAndNotByTheDatabase() {
            when(orders.findByOrderNumber("ORD-1002"))
                    .thenReturn(Optional.of(orderRow("ORD-1002", ADA, OrderStatus.SHIPPED)));

            assertThatThrownBy(() -> service.cancel(ADA, "ORD-1002", "too late"))
                    .isInstanceOf(ActionNotAllowedException.class)
                    .extracting(ex -> ((ActionNotAllowedException) ex).code())
                    .isEqualTo("ORDER_ALREADY_SHIPPED");

            verify(orders, never()).updateStatus(any(), any(), anyInt(), any());
        }

        @Test
        void losingTheOptimisticLockFailsInsteadOfSilentlyDoingNothing() {
            OrderRow order = orderRow("ORD-1001", ADA, OrderStatus.PROCESSING, new BigDecimal("249.00"), 7);
            when(orders.findByOrderNumber("ORD-1001")).thenReturn(Optional.of(order));
            // Another writer bumped the version between the read and the update.
            when(orders.updateStatus(any(), any(), eq(7), any())).thenReturn(0);

            assertThatThrownBy(() -> service.cancel(ADA, "ORD-1001", "race"))
                    .isInstanceOf(ConcurrentUpdateException.class);

            verifyNoInteractions(refunds);
        }
    }

    @Test
    void listingOrdersReturnsOnlyTheCallersOwn() {
        when(orders.findAllByCustomer(ADA)).thenReturn(List.of(
                orderRow("ORD-1001", ADA, OrderStatus.PROCESSING),
                orderRow("ORD-1003", ADA, OrderStatus.DELIVERED)));
        when(orders.findLines(any())).thenReturn(List.of());
        when(orders.findShipment(any())).thenReturn(Optional.empty());

        assertThat(service.listOrders(ADA)).extracting("orderNumber")
                .containsExactly("ORD-1001", "ORD-1003");
        verify(orders).findAllByCustomer(ADA);
    }

    private static String catchMessage(Runnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("expected an exception");
        }
        catch (RuntimeException ex) {
            return ex.getMessage();
        }
    }
}
