package com.armikom.sis.pool;

import com.armikom.sis.common.GlobalExceptionHandler.ErrorResponseBody;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Writes {@link PoolException} as {@code {code, message, details}} with the exception's own message. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PoolExceptionHandler {

    @ExceptionHandler(PoolException.class)
    public ResponseEntity<ErrorResponseBody> handle(PoolException e) {
        String code = switch (e.status()) {
            case BAD_REQUEST -> "VALIDATION_ERROR";
            case UNAUTHORIZED -> "UNAUTHORIZED";
            case FORBIDDEN -> "FORBIDDEN";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "CONFLICT";
            case TOO_MANY_REQUESTS -> "RATE_LIMITED";
            case BAD_GATEWAY -> "BAD_GATEWAY";
            default -> e.status().name();
        };
        return ResponseEntity.status(e.status()).body(new ErrorResponseBody(code, e.getMessage(), Map.of()));
    }
}
