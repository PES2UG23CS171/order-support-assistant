package com.acme.ordersupport.order;

import com.acme.ordersupport.order.model.RefundView;
import com.acme.ordersupport.order.repo.RefundRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only view of money in flight. Refunds are created as a side effect of a
 * cancellation or a return, never on their own, so there is no "issue a refund"
 * entry point for anything -- including the assistant -- to reach for.
 */
@Service
public class RefundService {

    private final OrderService orderService;
    private final RefundRepository refunds;

    public RefundService(OrderService orderService, RefundRepository refunds) {
        this.orderService = orderService;
        this.refunds = refunds;
    }

    @Transactional(readOnly = true)
    public List<RefundView> refundsForOrder(UUID customerId, String orderNumber) {
        return refunds.findByOrder(orderService.requireOwned(customerId, orderNumber).id());
    }
}
