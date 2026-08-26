package com.acme.ordersupport.web;

import com.acme.ordersupport.agent.ConversationNotFoundException;
import com.acme.ordersupport.agent.ToolIdentityMismatchException;
import com.acme.ordersupport.agent.confirm.ConfirmationErrors;
import com.acme.ordersupport.order.error.ActionNotAllowedException;
import com.acme.ordersupport.order.error.ConcurrentUpdateException;
import com.acme.ordersupport.order.error.OrderNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 7807 responses. Status codes are chosen so a client can tell the three
 * interesting cases apart without parsing prose: 404 means it is not yours or not
 * there, 409 means the rules say no or someone got there first, 422 means you sent
 * something malformed.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail orderNotFound(OrderNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Order not found", ex.getMessage(), "ORDER_NOT_FOUND");
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    ProblemDetail conversationNotFound(ConversationNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Conversation not found", ex.getMessage(),
                "CONVERSATION_NOT_FOUND");
    }

    @ExceptionHandler(ConfirmationErrors.NotFound.class)
    ProblemDetail confirmationNotFound(ConfirmationErrors.NotFound ex) {
        return problem(HttpStatus.NOT_FOUND, "Confirmation not found", ex.getMessage(),
                "CONFIRMATION_NOT_FOUND");
    }

    @ExceptionHandler(ConfirmationErrors.Expired.class)
    ProblemDetail confirmationExpired(ConfirmationErrors.Expired ex) {
        return problem(HttpStatus.GONE, "Confirmation expired", ex.getMessage(), "CONFIRMATION_EXPIRED");
    }

    @ExceptionHandler(ConfirmationErrors.AlreadyDecided.class)
    ProblemDetail confirmationAlreadyDecided(ConfirmationErrors.AlreadyDecided ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Confirmation already used", ex.getMessage(),
                "CONFIRMATION_ALREADY_DECIDED");
        problem.setProperty("pendingActionStatus", ex.status().name());
        return problem;
    }

    @ExceptionHandler(ActionNotAllowedException.class)
    ProblemDetail actionNotAllowed(ActionNotAllowedException ex) {
        return problem(HttpStatus.CONFLICT, "Action not allowed", ex.getMessage(), ex.code());
    }

    @ExceptionHandler(ConcurrentUpdateException.class)
    ProblemDetail concurrentUpdate(ConcurrentUpdateException ex) {
        return problem(HttpStatus.CONFLICT, "Concurrent update", ex.getMessage(), "CONCURRENT_UPDATE");
    }

    @ExceptionHandler(ToolIdentityMismatchException.class)
    ProblemDetail identityMismatch(ToolIdentityMismatchException ex) {
        // Should be unreachable. If it ever fires, it is a bug worth a stack trace.
        log.error("Tool identity mismatch", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Session error",
                "The session could not be verified. Please start a new conversation.",
                "SESSION_IDENTITY_MISMATCH");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("The request body is not valid.");
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid request", detail, "VALIDATION_FAILED");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
