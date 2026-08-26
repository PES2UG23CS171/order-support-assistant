package com.acme.ordersupport.order;

import com.acme.ordersupport.order.error.ActionNotAllowedException;
import com.acme.ordersupport.order.error.ConcurrentUpdateException;
import com.acme.ordersupport.order.error.OrderNotFoundException;
import com.acme.ordersupport.order.model.CancellationResult;
import com.acme.ordersupport.order.model.CommercePolicy;
import com.acme.ordersupport.order.model.Eligibility;
import com.acme.ordersupport.order.model.OrderStatus;
import com.acme.ordersupport.order.model.OrderView;
import com.acme.ordersupport.order.model.RefundStatus;
import com.acme.ordersupport.order.model.RefundView;
import com.acme.ordersupport.order.repo.OrderRepository;
import com.acme.ordersupport.order.repo.OrderRow;
import com.acme.ordersupport.order.repo.RefundRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orders, as seen by every client.
 *
 * <p>This class is the whole security argument for the assistant. The model never
 * reaches a repository, a connection or a SQL string; it calls a tool, the tool
 * calls a method here, and every method here starts by proving that the order it
 * was handed belongs to the caller. A REST client written by the mobile team goes
 * through exactly the same door and gets exactly the same refusals.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final Clock clock;

    public OrderService(OrderRepository orders, RefundRepository refunds, Clock clock) {
        this.orders = orders;
        this.refunds = refunds;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OrderView lookup(UUID customerId, String orderNumber) {
        return toView(requireOwned(customerId, orderNumber));
    }

    @Transactional(readOnly = true)
    public List<OrderView> listOrders(UUID customerId) {
        return orders.findAllByCustomer(customerId).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public Eligibility assessCancellation(UUID customerId, String orderNumber) {
        return cancellationEligibility(requireOwned(customerId, orderNumber));
    }

    /**
     * Cancels an order and, when payment was already captured, opens the refund.
     *
     * <p>Eligibility is re-checked here even when the caller checked a moment ago:
     * between a proposal and its confirmation the warehouse may have printed a
     * label, and the row as it stands now is the only thing worth trusting.
     */
    @Transactional
    public CancellationResult cancel(UUID customerId, String orderNumber, String reason) {
        OrderRow order = requireOwned(customerId, orderNumber);

        Eligibility eligibility = cancellationEligibility(order);
        if (!eligibility.allowed()) {
            throw ActionNotAllowedException.of(eligibility);
        }

        Instant now = clock.instant();
        if (orders.updateStatus(order.id(), OrderStatus.CANCELLED, order.version(), now) != 1) {
            throw new ConcurrentUpdateException(orderNumber);
        }

        RefundView refund = null;
        if (order.status().paymentCaptured() && order.totalAmount().signum() > 0) {
            refund = refunds.insert(
                    UUID.randomUUID(), order.id(), order.orderNumber(), null,
                    RefundStatus.PENDING, order.totalAmount(), order.currency(),
                    "ORIGINAL_PAYMENT_METHOD", now,
                    LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(CommercePolicy.CANCELLATION_SETTLEMENT_DAYS),
                    null);
        }

        log.info("Cancelled order {} for customer {} (reason={})", orderNumber, customerId, reason);
        String message = refund == null
                ? "Order %s is cancelled. No payment had been captured, so there is nothing to refund."
                        .formatted(orderNumber)
                : "Order %s is cancelled. A full refund of %s %s is on its way back to your original payment method."
                        .formatted(orderNumber, order.currency(), order.totalAmount());
        return new CancellationResult(orderNumber, OrderStatus.CANCELLED, refund, message);
    }

    /**
     * The authorization choke point. Every read and every write in this service
     * passes through it, so "can customer X touch order Y" is answered in one
     * place instead of at each call site.
     */
    OrderRow requireOwned(UUID customerId, String orderNumber) {
        OrderRow order = orders.findByOrderNumber(orderNumber).orElseThrow(
                () -> new OrderNotFoundException(orderNumber));
        if (!order.customerId().equals(customerId)) {
            // The caller is told only that it was not found. The real reason is
            // recorded here, where it is useful and not exploitable.
            log.warn("Customer {} attempted to access order {} owned by {}",
                    customerId, orderNumber, order.customerId());
            throw new OrderNotFoundException(orderNumber);
        }
        return order;
    }

    private Eligibility cancellationEligibility(OrderRow order) {
        if (order.status() == OrderStatus.CANCELLED) {
            return Eligibility.denied("ALREADY_CANCELLED",
                    "Order %s has already been cancelled.".formatted(order.orderNumber()));
        }
        if (!order.status().cancellable()) {
            return Eligibility.denied("ORDER_ALREADY_SHIPPED",
                    ("Order %s has already left the warehouse (status %s), so it can no longer be cancelled. "
                     + "You can refuse the delivery or start a return once it arrives.")
                            .formatted(order.orderNumber(), order.status()));
        }
        return Eligibility.allowed(
                "Order %s has not shipped yet and can be cancelled.".formatted(order.orderNumber()));
    }

    OrderView toView(OrderRow order) {
        return new OrderView(order.id(), order.orderNumber(), order.status(), order.currency(),
                order.totalAmount(), order.placedAt(), order.cancelledAt(), order.shippingCity(),
                order.shippingCountry(), order.version(),
                orders.findLines(order.id()),
                orders.findShipment(order.id()).orElse(null));
    }
}
