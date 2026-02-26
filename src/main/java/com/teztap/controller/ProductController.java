package com.teztap.controller;

import com.teztap.model.Product;
import com.teztap.repository.ProductRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductRepository productRepository;

    public ProductController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    // 1️⃣ Get all products
    @GetMapping
    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    // 2️⃣ Get product by ID
    @GetMapping("/{id}")
    public Product getProductById(@PathVariable Long id) {
        Optional<Product> product = productRepository.findById(id);
        return product.orElse(null); // return null if not found
    }

    // 3️⃣ Optional: Get products with pagination
    @GetMapping("/page")
    public List<Product> getProductsByPage(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        int start = page * size;
        List<Product> allProducts = productRepository.findAll();
        if (start >= allProducts.size()) return List.of();
        int end = Math.min(start + size, allProducts.size());
        return allProducts.subList(start, end);
    }
}
