package com.teztap.service.scraper;

import com.teztap.dto.ProductUpsertDto;
import com.teztap.model.Category;
import com.teztap.model.Market;
import com.teztap.model.Product;
import com.teztap.repository.CategoryRepository;
import com.teztap.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles all database writes for scrapers.
 *
 * Why a separate service?
 * Spring's @Transactional works via proxy — it only intercepts calls coming
 * FROM OUTSIDE the bean. When a scraper calls its own upsert() method, the proxy
 * is bypassed and @Transactional is silently ignored. By putting persistence in a
 * separate bean, every call goes through the proxy and transactions work correctly.
 *
 * Each upsert runs in REQUIRES_NEW so a DB failure on one product/category
 * is fully isolated and never rolls back saves for other items.
 */
@Service
@RequiredArgsConstructor
public class ScraperPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(ScraperPersistenceService.class);

    private final CategoryRepository categoryRepository;
    private final ProductRepository  productRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Category upsertCategory(String name, String url, Market market) {
        return categoryRepository.findByUrl(url).orElseGet(() -> {
            Category cat = new Category();
            cat.setName(name);
            cat.setUrl(url);
            cat.setMarket(market);
            Category saved = categoryRepository.save(cat);
            log.info("[DB] Saved category: {} → {}", name, url);
            return saved;
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Product upsertProduct(String name,
                                 String link,
                                 BigDecimal originalPrice,
                                 BigDecimal discountPrice,
                                 BigDecimal discountPct,
                                 String imageUrl,
                                 Category category,
                                 Market market) {

        Optional<Product> existing = productRepository.findByLink(link);

        if (existing.isPresent()) {
            Product p = existing.get();

            // Only write to DB if price data actually changed — keeps the
            // @UpdateTimestamp meaningful and avoids unnecessary dirty writes.
            boolean priceChanged = !p.getOriginalPrice().equals(originalPrice)
                    || !Objects.equals(p.getDiscountPrice(), discountPrice);

            if (priceChanged) {
                p.setOriginalPrice(originalPrice);
                p.setDiscountPrice(discountPrice);
                p.setDiscountPercentage(discountPct);
                p.setImageUrl(imageUrl);
                p.setCategory(category);
                p.setMarket(market);
                productRepository.save(p);
                log.debug("[DB] Updated: {}", name);
            }
            return p;
        }

        Product p = new Product();
        p.setName(name);
        p.setLink(link);
        p.setOriginalPrice(originalPrice);
        p.setDiscountPrice(discountPrice);
        p.setDiscountPercentage(discountPct);
        p.setImageUrl(imageUrl);
        p.setCategory(category);
        p.setMarket(market);
        productRepository.save(p);
        log.info("[DB] Saved product: {}", name);
        return p;
    }

    /**
     * Bulk upsert: one SELECT for all links, then one saveAll.
     * Replaces calling upsertProduct() N times per page.
     *
     * @Transactional(REQUIRES_NEW) keeps each page's batch in its own transaction
     * so a failure on one page doesn't roll back previously saved pages.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertProductsBulk(List<ProductUpsertDto> dtos) {
        if (dtos == null || dtos.isEmpty()) return;

        // Single SELECT to fetch all already-existing products for this batch
        List<String> links = dtos.stream()
                .map(ProductUpsertDto::getLink)
                .collect(Collectors.toList());

        Map<String, Product> existing = productRepository.findAllByLinkIn(links)
                .stream()
                .collect(Collectors.toMap(Product::getLink, p -> p));

        List<Product> toSave = new ArrayList<>(dtos.size());

        for (ProductUpsertDto dto : dtos) {
            Product product = existing.getOrDefault(dto.getLink(), new Product());

            product.setName(dto.getName());
            product.setLink(dto.getLink());
            product.setOriginalPrice(dto.getOriginalPrice());
            product.setDiscountPrice(dto.getDiscountPrice());
            product.setDiscountPercentage(dto.getDiscountPercentage());
            product.setImageUrl(dto.getImageUrl());
            product.setCategory(dto.getCategory());
            product.setMarket(dto.getMarket());

            toSave.add(product);
        }

        // One batch INSERT/UPDATE for the entire page
        productRepository.saveAll(toSave);
    }
}