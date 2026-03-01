package com.teztap.dto;

import java.util.List;

// Response
public record AuthResponse(String token, String username, List<String> roles) {}
