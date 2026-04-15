package com.teztap.controller;

import com.teztap.model.Product;
import com.teztap.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductRepository productRepository;

    public ProductController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    // Helper method to keep pagination logic DRY and fix 0-indexed Spring pages
    private Pageable createPageRequest(int page, int size) {
        int MAX_SIZE = 50;
        int finalSize = Math.max(1, Math.min(size, MAX_SIZE));
        int finalPage = Math.max(1, page) - 1; // Spring Data pages are 0-indexed
        return PageRequest.of(finalPage, finalSize);
    }

    // Get all products warning
    @GetMapping
    public String getAllProducts() {
        return "Use paged get to get products, getting all is waste of resource";
    }

    // Get product by ID
    @GetMapping("/{id}")
    public Product getProductById(@PathVariable Long id) {
        Optional<Product> product = productRepository.findById(id);
        return product.orElse(null);
    }

    // Get products with pagination
    @GetMapping("/page")
    public List<Product> getProductsByPage(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        Page<Product> productPage = productRepository.findAll(createPageRequest(page, size));
        return productPage.getContent();
    }

    // Get all discounted products
    @GetMapping("/discounts")
    public List<Product> getDiscountedProducts(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        Page<Product> productPage = productRepository.findByDiscountPercentageGreaterThan(
                BigDecimal.ZERO,
                createPageRequest(page, size)
        );
        return productPage.getContent();
    }

    // Get products by market name (e.g., /api/products/market/NEPTUN)
    @GetMapping("/market/{marketName}")
    public List<Product> getProductsByMarket(@PathVariable String marketName,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        Page<Product> productPage = productRepository.findByMarketNameIgnoreCase(
                marketName,
                createPageRequest(page, size)
        );
        return productPage.getContent();
    }

    // Get discounted products for a specific market
    @GetMapping("/market/{marketName}/discounts")
    public List<Product> getDiscountedProductsByMarket(@PathVariable String marketName,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        Page<Product> productPage = productRepository.findByMarketNameIgnoreCaseAndDiscountPercentageGreaterThan(
                marketName,
                BigDecimal.ZERO,
                createPageRequest(page, size)
        );
        return productPage.getContent();
    }

    // Get products by Category ID
    @GetMapping("/category/{categoryId}")
    public List<Product> getProductsByCategory(@PathVariable Long categoryId,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        Page<Product> productPage = productRepository.findByCategoryId(
                categoryId,
                createPageRequest(page, size)
        );
        return productPage.getContent();
    }

    // Search products by name (e.g., /api/products/search?keyword=laptop)
    @GetMapping("/search")
    public List<Product> searchProducts(@RequestParam String keyword,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        Page<Product> productPage = productRepository.findByNameContainingIgnoreCase(
                keyword,
                createPageRequest(page, size)
        );
        return productPage.getContent();
    }
}
