package com.teztap.service;

import com.teztap.dto.AddToCartRequest;
import com.teztap.dto.CartItemResponse;
import com.teztap.dto.CartResponse;
import com.teztap.dto.MarketCartResponse;
import com.teztap.model.CartItem;
import com.teztap.model.Market;
import com.teztap.model.Product;
import com.teztap.model.User;
import com.teztap.repository.CartItemRepository;
import com.teztap.repository.ProductRepository;
import com.teztap.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    private User getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public CartResponse getCart(String username) {
        User user = getUser(username);
        List<CartItem> items = cartItemRepository.findByUser(user);

        // 1. Group the cart items by their Product's Market
        Map<Market, List<CartItem>> groupedItems = items.stream()
                .collect(Collectors.groupingBy(item -> item.getProduct().getMarket()));

        List<MarketCartResponse> marketResponses = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;

        // 2. Process each market group
        for (Map.Entry<Market, List<CartItem>> entry : groupedItems.entrySet()) {
            Market market = entry.getKey();
            List<CartItem> marketItems = entry.getValue();

            // Convert items to DTOs
            List<CartItemResponse> itemResponses = marketItems.stream()
                    .map(this::toResponse)
                    .toList();

            // Calculate subtotal for just this market
            BigDecimal marketTotal = itemResponses.stream()
                    .map(CartItemResponse::subtotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Build the market group
            marketResponses.add(new MarketCartResponse(
                    market.getId(),
                    market.getName(),
                    itemResponses,
                    marketTotal
            ));

            // Add to the overall cart grand total
            grandTotal = grandTotal.add(marketTotal);
        }

        // 3. Return the newly structured response
        return new CartResponse(marketResponses, grandTotal);
    }

    public CartResponse addToCart(String username, AddToCartRequest req) {
        User user = getUser(username);
        Product product = productRepository.findById(req.productId())
                .orElseThrow(() -> new RuntimeException("Product not found"));

        CartItem item = cartItemRepository.findByUserAndProduct(user, product)
                .orElse(new CartItem());

        item.setUser(user);
        item.setProduct(product);
        item.setQuantity((item.getQuantity() == null ? 0 : item.getQuantity()) + req.quantity());
        cartItemRepository.save(item);

        return getCart(username);
    }

    public CartResponse updateQuantity(String username, Long cartItemId, Integer quantity) {
        CartItem item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new RuntimeException("Cart item not found"));

        if (!item.getUser().getUsername().equals(username)) {
            throw new RuntimeException("Not your cart item");
        }

        if (quantity <= 0) {
            cartItemRepository.delete(item);
        } else {
            item.setQuantity(quantity);
            cartItemRepository.save(item);
        }

        return getCart(username);
    }

    public CartResponse removeFromCart(String username, Long cartItemId) {
        CartItem item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new RuntimeException("Cart item not found"));

        if (!item.getUser().getUsername().equals(username)) {
            throw new RuntimeException("Not your cart item");
        }

        cartItemRepository.delete(item);
        return getCart(username);
    }

    public void clearCart(User user) {
        cartItemRepository.deleteByUser(user);
    }

    public List<CartItem> getCartItems(User user) {
        return cartItemRepository.findByUser(user);
    }

    private CartItemResponse toResponse(CartItem item) {
        Product p = item.getProduct();
        BigDecimal effectivePrice = p.getDiscountPrice() != null ? p.getDiscountPrice() : p.getOriginalPrice();
        BigDecimal subtotal = effectivePrice.multiply(BigDecimal.valueOf(item.getQuantity()));
        return new CartItemResponse(
                item.getId(), p.getId(), p.getName(), p.getImageUrl(),
                p.getOriginalPrice(), p.getDiscountPrice(),
                item.getQuantity(), subtotal
        );
    }

    @Transactional
    public void removeSelectedCartItems(String username, List<Long> cartItemIds) {
        User user = getUser(username);
        cartItemRepository.deleteByIdInAndUser(cartItemIds, user);
    }
}
