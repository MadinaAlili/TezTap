package com.teztap.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {
    @GetMapping("/")
    public String home() {
        return "<h1>Backend is working!</h1>";
    }

    @GetMapping("/health")
    public String health() {
        return "<h2>Status: OK</h2>";
    }
}