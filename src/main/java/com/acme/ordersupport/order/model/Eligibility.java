package com.acme.ordersupport.order.model;

/**
 * The answer to "may this happen?", separated from actually doing it.
 *
 * <p>Splitting the check out matters for the confirmation flow: proposing an
 * action runs the same rules the execution will run, so the summary shown to the
 * customer cannot promise something the service would then refuse. The execution
 * path re-runs the check inside its own transaction regardless -- a decision made
 * a minute ago is not evidence about the row as it stands now.
 *
 * @param code a stable machine-readable reason, safe to assert on in tests
 * @param explanation customer-facing wording, safe to show verbatim
 */
public record Eligibility(boolean allowed, String code, String explanation) {

    public static Eligibility allowed(String explanation) {
        return new Eligibility(true, "ALLOWED", explanation);
    }

    public static Eligibility denied(String code, String explanation) {
        return new Eligibility(false, code, explanation);
    }
}
