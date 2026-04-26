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
    public void upsertProductsBulk(List<ProductUpsertDto> dtos) {
        if (dtos == null || dtos.isEmpty()) return;

        // All dtos in one batch share the same market
        Market market = dtos.get(0).market();

        List<String> names = dtos.stream()
                .map(ProductUpsertDto::name)
                .collect(Collectors.toList());

        // Single SELECT by market + name
        Map<String, Product> existing = productRepository.findAllByMarketAndNameIn(market, names)
                .stream()
                .collect(Collectors.toMap(Product::getName, p -> p));

        List<Product> toSave = new ArrayList<>(dtos.size());

        for (ProductUpsertDto dto : dtos) {
            Product product = existing.getOrDefault(dto.name(), new Product());

            product.setName(dto.name());
            product.setLink(dto.link());
            product.setOriginalPrice(dto.originalPrice());
            product.setDiscountPrice(dto.discountPrice());
            product.setDiscountPercentage(dto.discountPercentage());
            product.setImageUrl(dto.imageUrl());
            product.setCategory(dto.category());
            product.setMarket(dto.market());

            toSave.add(product);
        }

        productRepository.saveAll(toSave);
    }

//    @Transactional(propagation = Propagation.REQUIRES_NEW)
//    public Product upsertProduct(String name,
//                                 String link,
//                                 BigDecimal originalPrice,
//                                 BigDecimal discountPrice,
//                                 BigDecimal discountPct,
//                                 String imageUrl,
//                                 Category category,
//                                 Market market) {
//
//        Optional<Product> existing = productRepository.findByLink(link);
//
//        if (existing.isPresent()) {
//            Product p = existing.get();
//
//            // Only write to DB if price data actually changed — keeps the
//            // @UpdateTimestamp meaningful and avoids unnecessary dirty writes.
//            boolean priceChanged = !p.getOriginalPrice().equals(originalPrice)
//                    || !Objects.equals(p.getDiscountPrice(), discountPrice);
//
//            if (priceChanged) {
//                p.setOriginalPrice(originalPrice);
//                p.setDiscountPrice(discountPrice);
//                p.setDiscountPercentage(discountPct);
//                p.setImageUrl(imageUrl);
//                p.setCategory(category);
//                p.setMarket(market);
//                productRepository.save(p);
//                log.debug("[DB] Updated: {}", name);
//            }
//            return p;
//        }
//
//        Product p = new Product();
//        p.setName(name);
//        p.setLink(link);
//        p.setOriginalPrice(originalPrice);
//        p.setDiscountPrice(discountPrice);
//        p.setDiscountPercentage(discountPct);
//        p.setImageUrl(imageUrl);
//        p.setCategory(category);
//        p.setMarket(market);
//        productRepository.save(p);
//        log.info("[DB] Saved product: {}", name);
//        return p;
//    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Product upsertProduct(String name,
                                 String link,
                                 BigDecimal originalPrice,
                                 BigDecimal discountPrice,
                                 BigDecimal discountPct,
                                 String imageUrl,
                                 Category category,
                                 Market market) {

        // 1. Check existence using Name AND Market
        Optional<Product> existing = productRepository.findByNameAndMarket(name, market);

        if (existing.isPresent()) {
            Product p = existing.get();

            // 2. Safely check if price data actually changed
            boolean priceChanged = !Objects.equals(p.getOriginalPrice(), originalPrice)
                    || !Objects.equals(p.getDiscountPrice(), discountPrice);

            if (priceChanged) {
                p.setOriginalPrice(originalPrice);
                p.setDiscountPrice(discountPrice);
                p.setDiscountPercentage(discountPct);
                p.setImageUrl(imageUrl);
                p.setCategory(category);
                p.setLink(link); // Ensure link is updated in case it changed

                // Note: We don't need to update Name or Market here since we just matched on them

                productRepository.save(p);
                log.debug("[DB] Updated: {}", name);
            }
            return p;
        }

        // 3. If it doesn't exist, create a new one
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
//    @Transactional(propagation = Propagation.REQUIRES_NEW)
//    public void upsertProductsBulk(List<ProductUpsertDto> dtos) {
//        if (dtos == null || dtos.isEmpty()) return;
//
//        // Single SELECT to fetch all already-existing products for this batch
//        List<String> links = dtos.stream()
//                .map(ProductUpsertDto::link)
//                .collect(Collectors.toList());
//
//        Map<String, Product> existing = productRepository.findAllByLinkIn(links)
//                .stream()
//                .collect(Collectors.toMap(Product::getLink, p -> p));
//
//        List<Product> toSave = new ArrayList<>(dtos.size());
//
//        for (ProductUpsertDto dto : dtos) {
//            Product product = existing.getOrDefault(dto.link(), new Product());
//
//            product.setName(dto.name());
//            product.setLink(dto.link());
//            product.setOriginalPrice(dto.originalPrice());
//            product.setDiscountPrice(dto.discountPrice());
//            product.setDiscountPercentage(dto.discountPercentage());
//            product.setImageUrl(dto.imageUrl());
//            product.setCategory(dto.category());
//            product.setMarket(dto.market());
//
//            toSave.add(product);
//        }
//
//        // One batch INSERT/UPDATE for the entire page
//        productRepository.saveAll(toSave);
//    }
}