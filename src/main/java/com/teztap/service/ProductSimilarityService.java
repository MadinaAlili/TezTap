package com.teztap.service;

import com.teztap.model.Product;
import com.teztap.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductSimilarityService {

    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public Page<Product> getSimilarProducts(Long productId, Pageable pageable) {
        Product sourceProduct = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product with ID " + productId + " not found"));

        // 0.3 is a standard starting threshold for pg_trgm.
        // You can extract this to application.yml to tune it easily.
        double similarityThreshold = 0.6;

        return productRepository.findSimilarProducts(
                sourceProduct.getName(),
                productId,
                similarityThreshold,
                pageable
        );
    }
}
