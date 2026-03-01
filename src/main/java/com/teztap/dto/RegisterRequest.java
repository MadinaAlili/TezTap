package com.teztap.dto;

import java.util.Set;

// Request
public record RegisterRequest(String username, String email, String password, Set<String> roles) {}
