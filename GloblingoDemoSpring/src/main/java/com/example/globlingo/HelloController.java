package com.example.globlingo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    @GetMapping("/")
    public String home() {
        return "Welcome to Globlingo API! The backend is running. " +
                "Available endpoints: /api/videos, /api/saved, /api/subscriptions, /api/translate";
    }
}
