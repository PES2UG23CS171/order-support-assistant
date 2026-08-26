package com.acme.ordersupport.order.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * The numbers from the policy documents, in one place, as code.
 *
 * <p>The corpus under {@code resources/policies} is what the assistant retrieves
 * and quotes; the constants here are what the service layer enforces. Keeping them
 * side by side is the point -- if the two drift, the bot starts promising things
 * the backend will refuse, which is the failure mode a retrieval-grounded support
 * agent is most likely to hit in production.
 */
public final class CommercePolicy {

    /** returns-policy.md, "Standard return window". */
    public static final int STANDARD_RETURN_WINDOW_DAYS = 30;

    /** returns-policy.md, "Standard return window", extended window for furniture. */
    public static final int FURNITURE_RETURN_WINDOW_DAYS = 60;

    /** returns-policy.md, "Return shipping costs". */
    public static final BigDecimal CHANGE_OF_MIND_LABEL_FEE = new BigDecimal("6.95");

    /** refunds-policy.md, "Processing time" -- the optimistic end of 5 to 10 business days. */
    public static final int CANCELLATION_SETTLEMENT_DAYS = 7;

    private static final String FURNITURE = "FURNITURE";

    /**
     * Wording that shifts the return-shipping cost to us. Deliberately a small,
     * readable list rather than a model call: it decides who pays, so it needs to
     * be inspectable and unit-testable, and a support agent should not be able to
     * talk its way into waiving a fee.
     */
    private static final List<String> SELLER_AT_FAULT_PHRASES = List.of(
            "defect", "faulty", "broken", "damaged", "cracked", "not working", "doesn't work",
            "does not work", "stopped working", "wrong item", "wrong size sent", "missing part",
            "missing piece", "not as described", "arrived damaged", "dead on arrival");

    private CommercePolicy() {
    }

    /** Furniture-only orders get the extended window; any other mix gets the standard one. */
    public static int returnWindowDays(List<OrderLineView> returnableLines) {
        boolean furnitureOnly = !returnableLines.isEmpty()
                && returnableLines.stream().allMatch(line -> FURNITURE.equalsIgnoreCase(line.category()));
        return furnitureOnly ? FURNITURE_RETURN_WINDOW_DAYS : STANDARD_RETURN_WINDOW_DAYS;
    }

    public static boolean sellerAtFault(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        String normalised = reason.toLowerCase(Locale.ROOT);
        return SELLER_AT_FAULT_PHRASES.stream().anyMatch(normalised::contains);
    }
}
