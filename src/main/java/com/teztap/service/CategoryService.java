package com.teztap.service;

import com.teztap.dto.CategoryDto;
import com.teztap.dto.CategoryWithProductsDto;
import com.teztap.dto.ProductDto;
import com.teztap.model.Category;
import com.teztap.model.Product;
import com.teztap.repository.CategoryRepository;
import com.teztap.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryService {
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public List<CategoryDto> getAllCategories() {
        return categoryRepository.findAll()
                .stream()
                .map(this::mapToCategoryDto)
                .toList();
    }

    public List<CategoryDto> getCategoriesByMarketName(String marketName) {
        List<Category> categories = categoryRepository.findCategoriesByMarketNameIgnoreCase(marketName);

        return categories.stream()
                .map(this::mapToCategoryDto)
                .collect(Collectors.toList());
    }

    public List<CategoryDto> getCategoriesByMarketId(Long marketId) {
        List<Category> categories = categoryRepository.findCategoriesByMarketId(marketId);
        // Map your Category entities to CategoryDto objects here
        return categories.stream()
                .map(this::mapToCategoryDto) // Assuming you have a mapping method
                .collect(Collectors.toList());
    }

    public CategoryDto getCategoryById(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Category not found with ID: " + id));
        // Note: Replace RuntimeException with your project's custom exception (e.g., ResourceNotFoundException)

        return mapToCategoryDto(category);
    }

    public List<ProductDto> getProductsByCategoryName(String categoryName, int page, int size) {
        // Note: Spring Data pages are 0-indexed, so we do (page - 1)
        Pageable pageable = PageRequest.of(page - 1, size);

        // This executes a highly optimized LIMIT/OFFSET query
        return productRepository.findByCategoryNameIgnoreCase(categoryName, pageable)
                .stream()
                .map(this::mapToProductDto) // Your existing DTO mapping logic
                .toList();
    }

    public int getTotalPagesForCategoryProducts(Long categoryId, int size) {
        long totalProducts = productRepository.countByCategoryId(categoryId);
        return (int) Math.ceil((double) totalProducts / size);
    }

    public List<ProductDto> getProductsByCategoryId(Long categoryId, int page, int size) {
        // Spring Data pages are 0-indexed
        Pageable pageable = PageRequest.of(page - 1, size);

        return productRepository.findByCategoryId(categoryId, pageable)
                .stream()
                .map(this::mapToProductDto) // Replace with your actual mapping logic
                .toList();
    }

    // ==========================================
    // MAPPER FUNCTIONS
    // ==========================================

    /**
     * Maps a Category entity to a basic CategoryDto (without products)
     */
    private CategoryDto mapToCategoryDto(Category category) {
        if (category == null) {
            return null;
        }

        return new CategoryDto(
                category.getId(),
                category.getName(),
                category.getUrl(),
                category.getCreated()
        );
    }

    /**
     * Maps a Product entity to your existing ProductDto.
     * Note: Adjust the getters to match your actual Product entity fields!
     */
    private ProductDto mapToProductDto(Product product) {
        if (product == null) {
            return null;
        }

        // Assuming ProductDto is a Record or has a standard constructor.
        // Update these parameters to match exactly what your ProductDto expects.
        return new ProductDto(
                product.getId(),
                product.getName(),
                product.getOriginalPrice(),
                product.getDiscountPrice(),
                product.getDiscountPercentage(),
                product.getLink(),
                product.getImageUrl(),
                product.getCategory() != null ? product.getCategory().getId() : null,
                product.getMarket() != null ? product.getMarket().getId() : null
        );
    }

    /**
     * Maps a Category entity to a CategoryWithProductsDto.
     * (Only needed if you decided to keep Endpoint #4)
     */
    private CategoryWithProductsDto mapToCategoryWithProductsDto(Category category) {
        if (category == null) {
            return null;
        }

        // Safely map the list of products, handling potential nulls
        List<ProductDto> productDtos = category.getProducts() != null
                ? category.getProducts().stream()
                .map(this::mapToProductDto)
                .toList()
                : List.of();

        return new CategoryWithProductsDto(
                category.getId(),
                category.getName(),
                category.getUrl(),
                category.getCreated(),
                productDtos
        );
    }
}
