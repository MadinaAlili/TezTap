package com.teztap.controller;

import com.teztap.model.Product;
import com.teztap.service.ProductSimilarityService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductSimilarityController {

    private final ProductSimilarityService similarityService;

    @GetMapping("/{id}/similar")
    public ResponseEntity<Page<Product>> getSimilarProducts(
            @PathVariable Long id,
            @PageableDefault(size = 20) Pageable pageable) {

        Page<Product> similarProducts = similarityService.getSimilarProducts(id, pageable);
        return ResponseEntity.ok(similarProducts);
    }
//      THOSE COMMANDS NEEDS TO BE RUN ON DB TO ENABLE TRIGRAM SEARCH
//    CREATE EXTENSION IF NOT EXISTS pg_trgm;
//
//-- Creates a GiST index to make the trigram similarity search highly performant
//    CREATE INDEX idx_products_name_trgm ON products USING gist (name gist_trgm_ops);
}
