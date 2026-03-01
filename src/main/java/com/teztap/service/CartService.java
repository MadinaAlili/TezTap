package com.teztap.service;

import com.teztap.dto.AddToCartRequest;
import com.teztap.dto.CartItemResponse;
import com.teztap.dto.CartResponse;
import com.teztap.model.CartItem;
import com.teztap.model.Product;
import com.teztap.model.User;
import com.teztap.repository.CartItemRepository;
import com.teztap.repository.ProductRepository;
import com.teztap.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

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
        List<CartItemResponse> responses = items.stream().map(this::toResponse).toList();
        BigDecimal total = responses.stream()
                .map(CartItemResponse::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponse(responses, total);
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
}
