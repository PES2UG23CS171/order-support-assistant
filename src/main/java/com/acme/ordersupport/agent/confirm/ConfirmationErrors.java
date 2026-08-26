package com.acme.ordersupport.agent.confirm;

import java.util.UUID;

/** The four ways redeeming a confirmation token can fail. */
public final class ConfirmationErrors {

    private ConfirmationErrors() {
    }

    /** No such token, or it belongs to another customer -- deliberately the same answer. */
    public static class NotFound extends RuntimeException {
        public NotFound(UUID token) {
            super("No pending action %s is awaiting your confirmation.".formatted(token));
        }
    }

    public static class Expired extends RuntimeException {
        public Expired(UUID token) {
            super(("The confirmation for %s has expired. Ask again and a fresh confirmation will be "
                   + "prepared.").formatted(token));
        }
    }

    /** Already approved, rejected, or being executed by a request that got there first. */
    public static class AlreadyDecided extends RuntimeException {
        private final PendingActionStatus status;

        public AlreadyDecided(UUID token, PendingActionStatus status) {
            super("Pending action %s has already been %s.".formatted(token, status.name().toLowerCase()));
            this.status = status;
        }

        public PendingActionStatus status() {
            return status;
        }
    }
}
