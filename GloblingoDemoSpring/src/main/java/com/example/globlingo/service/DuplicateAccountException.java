package com.example.globlingo.service;

public class DuplicateAccountException extends RuntimeException {
    public DuplicateAccountException() {
        super("An account with this email already exists");
    }
}
