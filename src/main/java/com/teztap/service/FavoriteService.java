package com.teztap.service;

import com.teztap.dto.FavoriteResponse;
import com.teztap.model.Favorite;
import com.teztap.model.Product;
import com.teztap.model.User;
import com.teztap.repository.FavoriteRepository;
import com.teztap.repository.ProductRepository;
import com.teztap.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    private User getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @Transactional
    public void toggleFavorite(String username, Long productId) {
        User user = getUser(username);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found"));

        favoriteRepository.findByUserAndProduct(user, product).ifPresentOrElse(
                // If it exists, remove it (Un-favorite)
                favoriteRepository::delete,
                // If it doesn't exist, add it (Favorite)
                () -> favoriteRepository.save(new Favorite(user, product))
        );
    }

    public List<FavoriteResponse> getUserFavorites(String username, int page, int size) {
        User user = getUser(username);
        PageRequest pageRequest = PageRequest.of(Math.max(0, page - 1), size); // 0-indexed pagination

        Page<Favorite> favorites = favoriteRepository.findByUserOrderByCreatedDesc(user, pageRequest);

        return favorites.getContent().stream().map(fav -> {
            Product p = fav.getProduct();
            return new FavoriteResponse(
                    fav.getId(),
                    p.getId(),
                    p.getName(),
                    p.getImageUrl(),
                    p.getOriginalPrice(),
                    p.getDiscountPrice(),
                    p.getMarket() != null ? p.getMarket().getName() : null,
                    fav.getCreated()
            );
        }).toList();
    }

    public boolean isFavorited(String username, Long productId) {
        User user = getUser(username);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found"));
        return favoriteRepository.existsByUserAndProduct(user, product);
    }

    public Map<Long, Boolean> checkFavoritesInBulk(String username, List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Collections.emptyMap();
        }

        User user = getUser(username);

        // 1. Get the Set of IDs that are actually favorited
        Set<Long> favoritedIds = favoriteRepository
                .findFavoritedProductIdsByUserAndProductIds(user, productIds);

        // 2. Build a map of all requested IDs -> true/false
        Map<Long, Boolean> result = new HashMap<>();
        for (Long id : productIds) {
            result.put(id, favoritedIds.contains(id));
        }

        return result;
    }
}