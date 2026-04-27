package com.teztap.controller;

import com.teztap.model.Product;
import com.teztap.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    // Helper method to keep pagination logic DRY and fix 0-indexed Spring pages
    private Pageable createPageRequest(int page, int size, String sortBy, String sortDir) {
        int MAX_SIZE = 50;
        int finalSize = Math.max(1, Math.min(size, MAX_SIZE));
        int finalPage = Math.max(1, page) - 1; // Spring Data pages are 0-indexed

        if (sortBy.equalsIgnoreCase("price")) {
            sortBy = "originalPrice";
        }

        // Create the Sort object dynamically based on user input
        Sort sort = sortDir.equalsIgnoreCase(Sort.Direction.ASC.name())
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        return PageRequest.of(finalPage, finalSize, sort);
    }

    // Get all products warning
    @GetMapping
    public String getAllProducts() {
        return "Use paged get to get products, getting all is waste of resource";
    }

    // Get product by ID
    @GetMapping("/{id:\\d+}")
    public Product getProductById(@PathVariable Long id) {
        Optional<Product> product = productRepository.findById(id);
        return product.orElse(null);
    }

    @GetMapping("/filter")
    public List<Product> filterProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String marketName,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "false") boolean onlyDiscounted,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sortBy, // Default sort by ID
            @RequestParam(defaultValue = "asc") String sortDir // Default ascending
    ) {
        Pageable pageable = createPageRequest(page, size, sortBy, sortDir);

        Page<Product> productPage = productRepository.findWithDynamicFilters(
                keyword,
                marketName,
                categoryId,
                onlyDiscounted,
                pageable
        );

        return productPage.getContent();
    }
}
