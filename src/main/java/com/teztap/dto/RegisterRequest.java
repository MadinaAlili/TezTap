package com.teztap.dto;

import java.util.Set;

// Request
public record RegisterRequest(String username, String fullName, String email, String phoneNumber, String password, Set<String> roles) {}
