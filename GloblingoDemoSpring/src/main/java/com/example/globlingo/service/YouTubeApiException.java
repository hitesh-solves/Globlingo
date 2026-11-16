package com.example.globlingo.service;

public class YouTubeApiException extends RuntimeException {
    private final int statusCode;

    public YouTubeApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
