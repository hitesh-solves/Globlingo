package com.example.globlingo.service;

import org.springframework.http.HttpStatus;

public class AiTutorException extends RuntimeException {
    private final HttpStatus status;

    public AiTutorException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }
}
