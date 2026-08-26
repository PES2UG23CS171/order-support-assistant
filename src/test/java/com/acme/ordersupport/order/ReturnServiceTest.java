package com.acme.ordersupport.order;

import static com.acme.ordersupport.testsupport.Fixtures.ADA;
import static com.acme.ordersupport.testsupport.Fixtures.deliveredDaysAgo;
import static com.acme.ordersupport.testsupport.Fixtures.fixedClock;
import static com.acme.ordersupport.testsupport.Fixtures.inTransit;
import static com.acme.ordersupport.testsupport.Fixtures.line;
import static com.acme.ordersupport.testsupport.Fixtures.orderRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ordersupport.order.error.ActionNotAllowedException;
import com.acme.ordersupport.order.model.Eligibility;
import com.acme.ordersupport.order.model.OrderLineView;
import com.acme.ordersupport.order.model.OrderStatus;
import com.acme.ordersupport.order.model.RefundStatus;
import com.acme.ordersupport.order.model.ReturnResult;
import com.acme.ordersupport.order.model.ReturnStatus;
import com.acme.ordersupport.order.model.ReturnView;
import com.acme.ordersupport.order.model.ShipmentView;
import com.acme.ordersupport.order.repo.OrderRepository;
import com.acme.ordersupport.order.repo.RefundRepository;
import com.acme.ordersupport.order.repo.ReturnRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** One test per denial branch, because each one is a sentence the bot will say to a customer. */
@ExtendWith(MockitoExtension.class)
class ReturnServiceTest {

    @Mock
    private OrderRepository orders;

    @Mock
    private ReturnRepository returns;

    @Mock
    private RefundRepository refunds;

    private ReturnService service;

    @BeforeEach
    void setUp() {
        OrderService orderService = new OrderService(orders, refunds, fixedClock());
        service = new ReturnService(orderService, orders, returns, refunds, fixedClock());
    }

    private void givenOrder(OrderStatus status, List<OrderLineView> lines, ShipmentView shipment) {
        var row = orderRow("ORD-1003", ADA, status, new BigDecimal("89.99"), 2);
        when(orders.findByOrderNumber("ORD-1003")).thenReturn(Optional.of(row));
        when(orders.findLines(row.id())).thenReturn(lines);
        when(orders.findShipment(row.id())).thenReturn(Optional.ofNullable(shipment));
    }

    @Test
    @DisplayName("an undelivered order cannot be returned")
    void undelivered() {
        givenOrder(OrderStatus.SHIPPED, List.of(line("HP-1", "AUDIO", true, "89.99")), inTransit());

        Eligibility eligibility = service.assessReturn(ADA, "ORD-1003");

        assertThat(eligibility.allowed()).isFalse();
        assertThat(eligibility.code()).isEqualTo("ORDER_NOT_DELIVERED");
    }

    @Test
    @DisplayName("a delivered order with no recorded delivery date is escalated, not guessed at")
    void missingDeliveryDate() {
        givenOrder(OrderStatus.DELIVERED, List.of(line("HP-1", "AUDIO", true, "89.99")), null);

        assertThat(service.assessReturn(ADA, "ORD-1003").code()).isEqualTo("DELIVERY_DATE_UNKNOWN");
    }

    @Test
    @DisplayName("an order of only final-sale items has nothing returnable on it")
    void nothingReturnable() {
        givenOrder(OrderStatus.DELIVERED, List.of(line("GIFTCARD-100", "GIFT_CARD", false, "100.00")),
                deliveredDaysAgo(3));

        Eligibility eligibility = service.assessReturn(ADA, "ORD-1003");

        assertThat(eligibility.code()).isEqualTo("NO_RETURNABLE_ITEMS");
        assertThat(eligibility.explanation()).contains("final sale");
    }

    @Test
    @DisplayName("past the 30-day window is refused, and the refusal shows both numbers")
    void windowExpired() {
        givenOrder(OrderStatus.DELIVERED, List.of(line("HP-1", "AUDIO", true, "89.99")),
                deliveredDaysAgo(45));

        Eligibility eligibility = service.assessReturn(ADA, "ORD-1003");

        assertThat(eligibility.code()).isEqualTo("RETURN_WINDOW_EXPIRED");
        assertThat(eligibility.explanation()).contains("45 days ago").contains("30 days");
    }

    @Test
    @DisplayName("furniture keeps its 60-day window at day 45")
    void furnitureExtendedWindow() {
        givenOrder(OrderStatus.DELIVERED, List.of(line("CHAIR-1", "FURNITURE", true, "459.00")),
                deliveredDaysAgo(45));

        assertThat(service.assessReturn(ADA, "ORD-1003").allowed()).isTrue();
    }

    @Test
    @DisplayName("a second return cannot be opened while one is still in flight")
    void alreadyOpen() {
        givenOrder(OrderStatus.DELIVERED, List.of(line("HP-1", "AUDIO", true, "89.99")),
                deliveredDaysAgo(3));
        when(returns.findOpenByOrder(any())).thenReturn(Optional.of(new ReturnView(UUID.randomUUID(),
                "RMA-ABC12345", UUID.randomUUID(), "ORD-1003", ReturnStatus.IN_TRANSIT, "faulty", null,
                null)));

        assertThat(service.assessReturn(ADA, "ORD-1003").code()).isEqualTo("RETURN_ALREADY_OPEN");
    }

    @Test
    @DisplayName("no open return means a new one is allowed")
    void noOpenReturnDoesNotBlock() {
        // Which statuses count as open is ReturnStatus.open(); that the repository
        // filters on it correctly is covered against a real database in ReturnServiceIT.
        givenOrder(OrderStatus.DELIVERED, List.of(line("HP-1", "AUDIO", true, "89.99")),
                deliveredDaysAgo(3));
        when(returns.findOpenByOrder(any())).thenReturn(Optional.empty());

        assertThat(service.assessReturn(ADA, "ORD-1003").allowed()).isTrue();
    }

    @Test
    @DisplayName("change-of-mind returns carry the label fee")
    void changeOfMindDeductsTheLabelFee() {
        givenOrder(OrderStatus.DELIVERED, List.of(line("HP-1", "AUDIO", true, "89.99")),
                deliveredDaysAgo(3));
        when(orders.updateStatus(any(), eq(OrderStatus.RETURN_REQUESTED), anyInt(), eq(null))).thenReturn(1);

        ReturnResult result = service.initiateReturn(ADA, "ORD-1003", "changed my mind");

        assertThat(result.deductedFees()).isEqualByComparingTo("6.95");
        assertThat(result.estimatedRefund()).isEqualByComparingTo("83.04");
        assertThat(result.rmaNumber()).matches("RMA-[0-9A-F]{8}");
        assertThat(result.message()).contains("return label fee");
    }

    @Test
    @DisplayName("a fault waives the label fee")
    void faultWaivesTheFee() {
        givenOrder(OrderStatus.DELIVERED, List.of(line("HP-1", "AUDIO", true, "89.99")),
                deliveredDaysAgo(3));
        when(orders.updateStatus(any(), eq(OrderStatus.RETURN_REQUESTED), anyInt(), eq(null))).thenReturn(1);

        ReturnResult result = service.initiateReturn(ADA, "ORD-1003", "it arrived damaged");

        assertThat(result.deductedFees()).isEqualByComparingTo("0.00");
        assertThat(result.estimatedRefund()).isEqualByComparingTo("89.99");
    }

    @Test
    @DisplayName("the refund a return opens carries no settlement date, because none is known yet")
    void returnRefundHasNoPromisedDate() {
        givenOrder(OrderStatus.DELIVERED, List.of(line("HP-1", "AUDIO", true, "89.99")),
                deliveredDaysAgo(3));
        when(orders.updateStatus(any(), any(), anyInt(), any())).thenReturn(1);

        service.initiateReturn(ADA, "ORD-1003", "it arrived damaged");

        verify(refunds).insert(any(), any(), eq("ORD-1003"), any(), eq(RefundStatus.PENDING),
                eq(new BigDecimal("89.99")), eq("USD"), eq("ORIGINAL_PAYMENT_METHOD"), any(),
                eq(null), eq(null));
    }

    @Test
    @DisplayName("initiating a return outside the window throws instead of writing anything")
    void initiateOutsideWindowWritesNothing() {
        givenOrder(OrderStatus.DELIVERED, List.of(line("HP-1", "AUDIO", true, "89.99")),
                deliveredDaysAgo(45));

        assertThatThrownBy(() -> service.initiateReturn(ADA, "ORD-1003", "too late"))
                .isInstanceOf(ActionNotAllowedException.class);

        verify(returns, never()).insert(any(), any(), any(), any(), any(), any(), any(), any());
        verify(orders, never()).updateStatus(any(), any(), anyInt(), any());
    }
}
