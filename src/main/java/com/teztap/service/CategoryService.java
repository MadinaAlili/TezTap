package com.teztap.service;

import com.teztap.dto.CategoryDto;
import com.teztap.dto.CategoryWithProductsDto;
import com.teztap.dto.ProductDto;
import com.teztap.model.Category;
import com.teztap.model.Product;
import com.teztap.model.User;
import com.teztap.repository.CategoryRepository;
import com.teztap.repository.FavoriteRepository;
import com.teztap.repository.ProductRepository;
import com.teztap.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryService {
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final FavoriteRepository favoriteRepository;
    private final UserRepository userRepository;

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

    public List<ProductDto> getProductsByCategoryName(String categoryName, int page, int size, String username) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Product> productPage = productRepository.findByCategoryNameIgnoreCase(categoryName, pageable);
        List<Product> products = productPage.getContent();

        // Bulk fetch favorite status
        Set<Long> favoritedProductIds = getFavoritedProductIdsInBatch(products, username);

        // Map to Record using the updated method
        return products.stream()
                .map(product -> mapToProductDto(
                        product,
                        favoritedProductIds.contains(product.getId()) // Check status here
                ))
                .toList();
    }

    public int getTotalPagesForCategoryProducts(Long categoryId, int size) {
        long totalProducts = productRepository.countByCategoryId(categoryId);
        return (int) Math.ceil((double) totalProducts / size);
    }

    public List<ProductDto> getProductsByCategoryId(Long categoryId, int page, int size, String username) {
        Pageable pageable = PageRequest.of(page - 1, size);

        // 1. Fetch the page of products
        Page<Product> productPage = productRepository.findByCategoryId(categoryId, pageable);
        List<Product> products = productPage.getContent();

        // 2. Bulk fetch favorite status
        Set<Long> favoritedProductIds = getFavoritedProductIdsInBatch(products, username);

        // 3. Map to DTO
        return products.stream()
                .map(product -> mapToProductDto(
                        product,
                        favoritedProductIds.contains(product.getId()) // Check status here
                ))
                .toList();
    }

    private Set<Long> getFavoritedProductIdsInBatch(List<Product> products, String username) {
        // If user is not logged in, or there are no products, return an empty set
        if (username == null || products.isEmpty()) {
            return Collections.emptySet();
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<Long> productIds = products.stream().map(Product::getId).toList();

        return favoriteRepository.findFavoritedProductIdsByUserAndProductIds(user, productIds);
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
    private ProductDto mapToProductDto(Product product, boolean isFavorited) {
        if (product == null) {
            return null;
        }

        return new ProductDto(
                product.getId(),
                product.getName(),
                product.getOriginalPrice(),
                product.getDiscountPrice(),
                product.getDiscountPercentage(),
                product.getLink(),
                product.getImageUrl(),
                product.getCategory() != null ? product.getCategory().getId() : null,
                product.getMarket() != null ? product.getMarket().getId() : null,
                isFavorited //  Pass it into the constructor here
        );
    }

    /**
     * Maps a Category entity to a CategoryWithProductsDto.
     * (Only needed if you decided to keep Endpoint #4)
     */
    private CategoryWithProductsDto mapToCategoryWithProductsDto(Category category, Set<Long> favoritedProductIds) {
        if (category == null) {
            return null;
        }

        // Safely map the list of products, handling potential nulls
        List<ProductDto> productDtos = category.getProducts() != null
                ? category.getProducts().stream()
                // Replaced this::mapToProductDto with a lambda to pass the boolean
                .map(product -> mapToProductDto(
                        product,
                        favoritedProductIds != null && favoritedProductIds.contains(product.getId())
                ))
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
