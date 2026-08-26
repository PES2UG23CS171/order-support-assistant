package com.acme.ordersupport.agent.model;

import com.acme.ordersupport.order.model.RefundView;
import java.math.BigDecimal;
import java.util.List;

public record RefundSnapshot(String orderNumber, List<Line> refunds, String note) implements ToolResult {

    public record Line(String status, BigDecimal amount, String currency, String method,
                       String initiatedAt, String expectedSettlementDate, String settledAt,
                       String reference) {
    }

    public static RefundSnapshot from(String orderNumber, List<RefundView> refunds) {
        if (refunds.isEmpty()) {
            return new RefundSnapshot(orderNumber, List.of(),
                    "No refund has been opened on this order.");
        }
        List<Line> lines = refunds.stream()
                .map(refund -> new Line(refund.status().name(), refund.amount(), refund.currency(),
                        refund.method(), Text.iso(refund.initiatedAt()),
                        Text.iso(refund.expectedSettlementDate()), Text.iso(refund.settledAt()),
                        refund.externalReference()))
                .toList();
        return new RefundSnapshot(orderNumber, lines, null);
    }
}
