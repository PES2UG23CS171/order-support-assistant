package com.acme.ordersupport.order.error;

import com.acme.ordersupport.order.model.Eligibility;

/** A business rule refused the operation. The message is safe to show a customer. */
public class ActionNotAllowedException extends RuntimeException {

    private final String code;

    public ActionNotAllowedException(String code, String explanation) {
        super(explanation);
        this.code = code;
    }

    public static ActionNotAllowedException of(Eligibility eligibility) {
        return new ActionNotAllowedException(eligibility.code(), eligibility.explanation());
    }

    public String code() {
        return code;
    }
}
