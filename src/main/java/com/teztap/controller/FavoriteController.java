package com.teztap.controller;

import com.teztap.dto.FavoriteResponse;
import com.teztap.service.FavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    // 1. Get all favorites for the logged-in user (Paginated)
    @GetMapping
    public ResponseEntity<List<FavoriteResponse>> getMyFavorites(
            Authentication auth,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(favoriteService.getUserFavorites(auth.getName(), page, size));
    }

    // 2. Toggle a favorite (If it exists, removes it. If it doesn't, adds it)
    @PostMapping("/{productId}/toggle")
    public ResponseEntity<String> toggleFavorite(
            Authentication auth,
            @PathVariable Long productId) {

        favoriteService.toggleFavorite(auth.getName(), productId);
        return ResponseEntity.ok("Favorite status toggled successfully.");
    }

    // 3. Check if a specific product is favorited (Useful for UI heart icon state)
    @GetMapping("/{productId}/check")
    public ResponseEntity<Boolean> checkIfFavorited(
            Authentication auth,
            @PathVariable Long productId) {

        return ResponseEntity.ok(favoriteService.isFavorited(auth.getName(), productId));
    }

    // 4. Check multiple products at once
    @PostMapping("/check-bulk")
    public ResponseEntity<Map<Long, Boolean>> checkFavoritesInBulk(
            Authentication auth,
            @RequestBody List<Long> productIds) {

        return ResponseEntity.ok(favoriteService.checkFavoritesInBulk(auth.getName(), productIds));
    }
}