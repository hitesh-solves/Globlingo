package com.example.globlingo.controller;

import com.example.globlingo.service.YouTubeApiException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(YouTubeApiException.class)
    public ResponseEntity<ApiError> youtube(YouTubeApiException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(new ApiError(false, exception.getMessage(), null));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(false, "This record conflicts with existing data.", null));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> databaseUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(false, "Database storage is temporarily unavailable. Please try again.", null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> invalidBody() {
        return ResponseEntity.badRequest().body(new ApiError(false, "Request body is missing or invalid.", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        log.error("Request failed: path={} type={}", request.getRequestURI(), exception.getClass().getSimpleName(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(false, "The request could not be completed. Please retry.", null));
    }

    public record ApiError(boolean success, String message, Object data) {}
}
