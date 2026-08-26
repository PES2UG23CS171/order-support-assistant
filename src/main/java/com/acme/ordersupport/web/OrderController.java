package com.acme.ordersupport.web;

import com.acme.ordersupport.agent.SupportSession;
import com.acme.ordersupport.agent.audit.Actor;
import com.acme.ordersupport.agent.audit.AuditService;
import com.acme.ordersupport.order.OrderService;
import com.acme.ordersupport.order.RefundService;
import com.acme.ordersupport.order.ReturnService;
import com.acme.ordersupport.order.model.CancellationResult;
import com.acme.ordersupport.order.model.OrderView;
import com.acme.ordersupport.order.model.RefundView;
import com.acme.ordersupport.order.model.ReturnResult;
import com.acme.ordersupport.order.model.ReturnView;
import com.acme.ordersupport.config.CurrentCustomer;
import com.acme.ordersupport.web.dto.ReasonRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ordinary REST API, standing in for the mobile app or the agent console.
 *
 * <p>It exists to make a point that is otherwise easy to assert and hard to show:
 * these endpoints call exactly the same service methods the assistant's tools
 * call. Same authorization, same eligibility rules, same optimistic locking, same
 * audit rows -- only the {@link Actor} differs.
 *
 * <p>What this API does not have is the confirmation gate, and that is deliberate.
 * The gate is an agent-layer control, not a domain rule: a first-party client has
 * already shown the customer a confirmation dialog of its own. Domain rules apply
 * to everybody; the human-in-the-loop step applies to the caller that cannot be
 * trusted to have asked.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;
    private final ReturnService returnService;
    private final RefundService refundService;
    private final AuditService audit;
    private final CurrentCustomer currentCustomer;

    public OrderController(OrderService orderService, ReturnService returnService,
                           RefundService refundService, AuditService audit, CurrentCustomer currentCustomer) {
        this.orderService = orderService;
        this.returnService = returnService;
        this.refundService = refundService;
        this.audit = audit;
        this.currentCustomer = currentCustomer;
    }

    @GetMapping
    public List<OrderView> list() {
        return orderService.listOrders(currentCustomer.requireId());
    }

    @GetMapping("/{orderNumber}")
    public OrderView get(@PathVariable String orderNumber) {
        UUID customerId = currentCustomer.requireId();
        return audit.around("lookupOrder", Actor.REST_CLIENT, restSession(customerId),
                Map.of("orderNumber", orderNumber),
                () -> orderService.lookup(customerId, orderNumber));
    }

    @GetMapping("/{orderNumber}/refunds")
    public List<RefundView> refunds(@PathVariable String orderNumber) {
        UUID customerId = currentCustomer.requireId();
        return audit.around("checkRefundStatus", Actor.REST_CLIENT, restSession(customerId),
                Map.of("orderNumber", orderNumber),
                () -> refundService.refundsForOrder(customerId, orderNumber));
    }

    @GetMapping("/{orderNumber}/returns")
    public List<ReturnView> returns(@PathVariable String orderNumber) {
        return returnService.returnsForOrder(currentCustomer.requireId(), orderNumber);
    }

    @PostMapping("/{orderNumber}/cancel")
    public CancellationResult cancel(@PathVariable String orderNumber,
                                     @Valid @RequestBody(required = false) ReasonRequest request) {
        UUID customerId = currentCustomer.requireId();
        String reason = request == null ? "Cancelled via the customer portal"
                : request.reasonOr("Cancelled via the customer portal");
        return audit.around("cancelOrder", Actor.REST_CLIENT, restSession(customerId),
                Map.of("orderNumber", orderNumber, "reason", reason),
                () -> orderService.cancel(customerId, orderNumber, reason));
    }

    @PostMapping("/{orderNumber}/returns")
    public ReturnResult openReturn(@PathVariable String orderNumber,
                                   @Valid @RequestBody(required = false) ReasonRequest request) {
        UUID customerId = currentCustomer.requireId();
        String reason = request == null ? "Requested via the customer portal"
                : request.reasonOr("Requested via the customer portal");
        return audit.around("initiateReturn", Actor.REST_CLIENT, restSession(customerId),
                Map.of("orderNumber", orderNumber, "reason", reason),
                () -> returnService.initiateReturn(customerId, orderNumber, reason));
    }

    /** REST calls have no conversation, but they still belong in the same audit table. */
    private static SupportSession restSession(UUID customerId) {
        return new SupportSession(null, customerId);
    }
}
