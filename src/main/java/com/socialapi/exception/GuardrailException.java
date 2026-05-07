package com.socialapi.exception;

import org.springframework.http.HttpStatus;

public class GuardrailException extends RuntimeException {
    private final HttpStatus status;

    public GuardrailException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
