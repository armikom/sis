package com.armikom.sis.pool;

import org.springframework.http.HttpStatus;

/**
 * An error of the pool / agent modules with a human-readable English message (question-pool's texts), which the
 * UI shows as it is. Rendered by {@link PoolExceptionHandler} in the SIS error shape.
 */
public class PoolException extends RuntimeException {

    private final HttpStatus status;

    public PoolException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() { return status; }

    public static PoolException bad(String message) {
        return new PoolException(HttpStatus.BAD_REQUEST, message);
    }

    public static PoolException notFound(String message) {
        return new PoolException(HttpStatus.NOT_FOUND, message);
    }

    public static PoolException forbidden(String message) {
        return new PoolException(HttpStatus.FORBIDDEN, message);
    }

    public static PoolException conflict(String message) {
        return new PoolException(HttpStatus.CONFLICT, message);
    }

    public static PoolException questionNotFound() {
        return notFound("Question not found.");
    }

    public static PoolException chapterNotFound() {
        return notFound("Chapter not found.");
    }
}
