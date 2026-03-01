package com.teztap.controller;

import com.teztap.dto.AddToCartRequest;
import com.teztap.dto.CartResponse;
import com.teztap.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartResponse> getCart(Authentication auth) {
        return ResponseEntity.ok(cartService.getCart(auth.getName()));
    }

    @PostMapping("/add")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartResponse> addToCart(@RequestBody AddToCartRequest req, Authentication auth) {
        return ResponseEntity.ok(cartService.addToCart(auth.getName(), req));
    }

    @PutMapping("/item/{cartItemId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartResponse> updateQuantity(
            @PathVariable Long cartItemId,
            @RequestParam Integer quantity,
            Authentication auth) {
        return ResponseEntity.ok(cartService.updateQuantity(auth.getName(), cartItemId, quantity));
    }

    @DeleteMapping("/item/{cartItemId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartResponse> removeItem(@PathVariable Long cartItemId, Authentication auth) {
        return ResponseEntity.ok(cartService.removeFromCart(auth.getName(), cartItemId));
    }
}
