package com.teztap.controller;

import com.teztap.dto.CategoryDto;
import com.teztap.dto.ProductDto;
import com.teztap.service.CategoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    // 1. Get all categories (Basic details only)
    @GetMapping
    public ResponseEntity<List<CategoryDto>> getAllCategories() {
        return ResponseEntity.ok(categoryService.getAllCategories());
    }

    // 2. Get a specific category by ID
    @GetMapping("/{id:\\d+}")
    public ResponseEntity<CategoryDto> getCategoryById(@PathVariable Long id) {
        return ResponseEntity.ok(categoryService.getCategoryById(id));
    }

    // 3. Get products for a specific category by ID (Strictly Paginated)
    @GetMapping("/{categoryId}/products")
    public ResponseEntity<List<ProductDto>> getProductsByCategoryId(
            @PathVariable Long categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(categoryService.getProductsByCategoryId(categoryId, page, size));
    }

    // 4. Get products by category name with pagination
    @GetMapping("/category/{categoryName}/products")
    public ResponseEntity<List<ProductDto>> getProductsByCategoryName(
            @PathVariable String categoryName,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(categoryService.getProductsByCategoryName(categoryName, page, size));
    }

    // 5. Get total number of pages for a specific category's products
    @GetMapping("/{categoryId}/products/page-count")
    public ResponseEntity<Integer> getProductPageCount(
            @PathVariable Long categoryId,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(categoryService.getTotalPagesForCategoryProducts(categoryId, size));
    }

    // 6. Get all categories available in a specific market
    @GetMapping("/market/{marketId:\\d+}")
    public ResponseEntity<List<CategoryDto>> getCategoriesByMarketId(@PathVariable Long marketId) {
        return ResponseEntity.ok(categoryService.getCategoriesByMarketId(marketId));
    }

    // 7. Get all categories available in a specific market by its Name (e.g., /market/name/ARAZ)
    @GetMapping("/market/name/{marketName}")
    public ResponseEntity<List<CategoryDto>> getCategoriesByMarketName(@PathVariable String marketName) {
        return ResponseEntity.ok(categoryService.getCategoriesByMarketName(marketName));
    }
}