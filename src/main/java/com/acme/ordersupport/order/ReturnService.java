package com.acme.ordersupport.order;

import com.acme.ordersupport.order.error.ActionNotAllowedException;
import com.acme.ordersupport.order.error.ConcurrentUpdateException;
import com.acme.ordersupport.order.model.CommercePolicy;
import com.acme.ordersupport.order.model.Eligibility;
import com.acme.ordersupport.order.model.OrderLineView;
import com.acme.ordersupport.order.model.OrderStatus;
import com.acme.ordersupport.order.model.OrderView;
import com.acme.ordersupport.order.model.RefundStatus;
import com.acme.ordersupport.order.model.ReturnResult;
import com.acme.ordersupport.order.model.ReturnStatus;
import com.acme.ordersupport.order.model.ReturnView;
import com.acme.ordersupport.order.model.ShipmentView;
import com.acme.ordersupport.order.repo.OrderRepository;
import com.acme.ordersupport.order.repo.RefundRepository;
import com.acme.ordersupport.order.repo.ReturnRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Returns. Five rules decide whether an RMA may be opened, and the assistant can
 * state all five to a customer but can bypass none of them.
 */
@Service
public class ReturnService {

    private static final Logger log = LoggerFactory.getLogger(ReturnService.class);

    private final OrderService orderService;
    private final OrderRepository orders;
    private final ReturnRepository returns;
    private final RefundRepository refunds;
    private final Clock clock;

    public ReturnService(OrderService orderService, OrderRepository orders, ReturnRepository returns,
                         RefundRepository refunds, Clock clock) {
        this.orderService = orderService;
        this.orders = orders;
        this.returns = returns;
        this.refunds = refunds;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Eligibility assessReturn(UUID customerId, String orderNumber) {
        return returnEligibility(orderService.toView(orderService.requireOwned(customerId, orderNumber)));
    }

    @Transactional(readOnly = true)
    public List<ReturnView> returnsForOrder(UUID customerId, String orderNumber) {
        return returns.findByOrder(orderService.requireOwned(customerId, orderNumber).id());
    }

    @Transactional
    public ReturnResult initiateReturn(UUID customerId, String orderNumber, String reason) {
        OrderView order = orderService.toView(orderService.requireOwned(customerId, orderNumber));

        Eligibility eligibility = returnEligibility(order);
        if (!eligibility.allowed()) {
            throw ActionNotAllowedException.of(eligibility);
        }

        Instant now = clock.instant();
        List<OrderLineView> returnable = order.returnableLines();

        BigDecimal grossRefund = returnable.stream()
                .map(OrderLineView::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fees = CommercePolicy.sellerAtFault(reason)
                ? BigDecimal.ZERO
                : CommercePolicy.CHANGE_OF_MIND_LABEL_FEE;
        BigDecimal netRefund = grossRefund.subtract(fees).max(BigDecimal.ZERO);

        UUID returnId = UUID.randomUUID();
        String rmaNumber = newRmaNumber(returnId);
        returns.insert(returnId, rmaNumber, order.id(), order.orderNumber(),
                returnable.size() == 1 ? returnable.get(0).id() : null,
                ReturnStatus.REQUESTED, reason, now);

        // No expected settlement date yet: refunds-policy.md releases return refunds
        // only after the item is received and inspected, so promising a date here
        // would be a number we cannot stand behind.
        refunds.insert(UUID.randomUUID(), order.id(), order.orderNumber(), returnId,
                RefundStatus.PENDING, netRefund, order.currency(), "ORIGINAL_PAYMENT_METHOD",
                now, null, null);

        if (orders.updateStatus(order.id(), OrderStatus.RETURN_REQUESTED, order.version(), null) != 1) {
            throw new ConcurrentUpdateException(orderNumber);
        }

        log.info("Opened return {} on order {} for customer {}", rmaNumber, orderNumber, customerId);
        String message = ("Return %s is open for order %s. A prepaid label is on its way to the email on the "
                          + "order; drop the parcel with the carrier within 14 days. Expected refund: %s %s%s.")
                .formatted(rmaNumber, orderNumber, order.currency(), netRefund,
                        fees.signum() > 0 ? " after the %s %s return label fee".formatted(order.currency(), fees) : "");
        return new ReturnResult(orderNumber, rmaNumber, ReturnStatus.REQUESTED, netRefund, fees, message);
    }

    private Eligibility returnEligibility(OrderView order) {
        // Checked before the delivery state, not after. Opening a return moves the
        // order to RETURN_REQUESTED, so a status-first ordering answers the second
        // request with "this order is not delivered yet" -- which names a status the
        // customer can see is wrong, about an order they are holding in their hands.
        if (returns.findOpenByOrder(order.id()).isPresent()) {
            return Eligibility.denied("RETURN_ALREADY_OPEN",
                    ("Order %s already has an open return. Only one return can be in flight per order at a "
                     + "time.").formatted(order.orderNumber()));
        }

        if (order.status() == OrderStatus.RETURNED || order.status() == OrderStatus.REFUNDED) {
            return Eligibility.denied("ALREADY_RETURNED",
                    "Order %s has already been returned.".formatted(order.orderNumber()));
        }

        if (order.status() != OrderStatus.DELIVERED) {
            return Eligibility.denied("ORDER_NOT_DELIVERED",
                    ("Order %s is not delivered yet (status %s). A return can only be opened once the parcel "
                     + "has arrived.").formatted(order.orderNumber(), order.status()));
        }

        ShipmentView shipment = order.shipment();
        if (shipment == null || shipment.deliveredAt() == null) {
            return Eligibility.denied("DELIVERY_DATE_UNKNOWN",
                    ("We do not have a confirmed delivery date for order %s, so the return window cannot be "
                     + "calculated. An agent will need to look at this one.").formatted(order.orderNumber()));
        }

        List<OrderLineView> returnable = order.returnableLines();
        if (returnable.isEmpty()) {
            return Eligibility.denied("NO_RETURNABLE_ITEMS",
                    ("Everything on order %s is final sale (for example gift cards or downloads), so there is "
                     + "nothing on it that can be returned.").formatted(order.orderNumber()));
        }

        int windowDays = CommercePolicy.returnWindowDays(returnable);
        // Calendar days between the delivery date and today, not elapsed duration.
        // returns-policy.md measures the window "from the date the carrier marks the
        // parcel delivered", and a customer counting on their fingers agrees with
        // that. Duration.toDays() truncates, so a parcel delivered 45 days and two
        // minutes ago would be reported back to them as 44.
        long daysSinceDelivery = ChronoUnit.DAYS.between(
                LocalDate.ofInstant(shipment.deliveredAt(), ZoneOffset.UTC),
                LocalDate.now(clock));
        if (daysSinceDelivery > windowDays) {
            return Eligibility.denied("RETURN_WINDOW_EXPIRED",
                    ("Order %s was delivered %d days ago and the return window for it is %d days, so it is "
                     + "outside the window.").formatted(order.orderNumber(), daysSinceDelivery, windowDays));
        }

        return Eligibility.allowed(("Order %s was delivered %d days ago, inside its %d-day return window, and "
                                    + "has %d returnable item(s).")
                .formatted(order.orderNumber(), daysSinceDelivery, windowDays, returnable.size()));
    }

    private static String newRmaNumber(UUID returnId) {
        return "RMA-" + returnId.toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
