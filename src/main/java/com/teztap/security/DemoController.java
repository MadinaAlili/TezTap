package com.teztap.security;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DemoController {

    @GetMapping("/user/hello")
    @PreAuthorize("hasRole('ROLE_USER')")          // any logged-in user
    public String userHello() {
        return "Hello, User!";
    }

    @GetMapping("/admin/dashboard")
    @PreAuthorize("hasRole('ROLE_ADMIN')")          // admins only
    public String adminDashboard() {
        return "Admin dashboard";
    }

    @GetMapping("/debug/me")
    public ResponseEntity<?> debugMe(Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.ok("Authentication is NULL — filter didn't run");
        }

        Map<String, Object> info = new HashMap<>();
        info.put("name", authentication.getName());
        info.put("authenticated", authentication.isAuthenticated());
        info.put("authorities", authentication.getAuthorities().toString());
        info.put("principal_type", authentication.getPrincipal().getClass().getName());

        return ResponseEntity.ok(info);
    }

    @GetMapping("/mod/panel")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_MODERATOR')")
    public String modPanel() {
        return "Moderator panel";
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public String me(Authentication auth) {          // inject current user anywhere
        return "You are: " + auth.getName();
    }
}

//        ## Quick Usage Flow
//```
//POST /api/auth/register
//{ "username": "john", "email": "john@test.com", "password": "pass123", "roles": ["admin"] }
//
//POST /api/auth/login
//{ "username": "john", "password": "pass123" }
//→ { "token": "eyJ...", "username": "john", "roles": ["ROLE_ADMIN"] }
//
//GET /api/admin/dashboard
//Header: Authorization: Bearer eyJ...
//        → 200 OK  (or 403 if wrong role)