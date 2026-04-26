package com.teztap.service.scraper.pdfExtractor.service;

import com.teztap.dto.ProductUpsertDto;
import com.teztap.model.Category;
import com.teztap.model.Market;
import com.teztap.repository.CategoryRepository;
import com.teztap.repository.MarketRepository;
import com.teztap.service.scraper.ScraperPersistenceService;
import com.teztap.service.scraper.pdfExtractor.model.ExtractedProduct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CataloguePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(CataloguePersistenceService.class);

    private final ScraperPersistenceService scraperPersistenceService;
    private final CategoryRepository        categoryRepository;
    private final MarketRepository          marketRepository;

    public void persistAll(List<ExtractedProduct> products, String marketName) {
        System.err.println("[DEBUG] ----- persistAll STARTED for market: " + marketName + " -----");

        if (products == null || products.isEmpty()) {
            System.err.println("[DEBUG] ABORT: Product list is null or empty!");
            return;
        }

        System.err.println("[DEBUG] Received " + products.size() + " scraped products to process.");

        Market market = marketRepository.findByName(marketName).orElseThrow(
                () -> new IllegalArgumentException("Market not found: " + marketName));

        System.err.println("[DEBUG] Successfully fetched Market entity: " + market.getName());

        Map<String, Category> categoryCache = new HashMap<>();
        List<ProductUpsertDto> dtos = new ArrayList<>(products.size());

        for (ExtractedProduct ep : products) {
            try {
                Category category = resolveCategory(ep.getCategoryName(), market, categoryCache);
                BigDecimal originalPrice = ep.getOldPrice() != null ? ep.getOldPrice() : BigDecimal.ZERO;
                BigDecimal discountPrice = ep.getNewPrice() != null ? ep.getNewPrice() : BigDecimal.ZERO;
                BigDecimal discountPct   = ep.getDiscountPct() != null ? BigDecimal.valueOf(ep.getDiscountPct()) : BigDecimal.ZERO;

                String generatedLink = buildPseudoLink(marketName, ep.getName(), ep.getDetails());

                ProductUpsertDto dto = new ProductUpsertDto(
                        ep.getName() + (ep.getDetails() != null ? " " + ep.getDetails() : ""),
                        generatedLink,
                        originalPrice,
                        discountPrice,
                        discountPct,
                        ep.getMatchedImageUrl(),
                        category,
                        market
                );
                dtos.add(dto);
            } catch (Exception e) {
                System.err.println("[DEBUG ERROR] Failed to map product: '" + ep.getName() + "'");
                System.err.println("[DEBUG ERROR] Reason: " + e.getMessage());
                e.printStackTrace(); // CRITICAL: Shows you exactly which line caused the map to fail
                log.warn("[Persistence] Skipping '{}': {}", ep.getName(), e.getMessage());
            }
        }

        System.err.println("[DEBUG] Successfully mapped " + dtos.size() + " out of " + products.size() + " products.");

        // Deduplicate by link within this batch
        Map<String, ProductUpsertDto> deduped = new LinkedHashMap<>();
        for (ProductUpsertDto dto : dtos) {
            if (deduped.containsKey(dto.link())) {
                System.err.println("[DEBUG COLLISION] Duplicate link found: " + dto.link() + ". Overwriting previous!");
            }
            deduped.put(dto.link(), dto);
        }

        System.err.println("[DEBUG] After deduplication, " + deduped.size() + " products remain.");
        System.err.println("[DEBUG] Sending to scraperPersistenceService.upsertProductsBulk...");

        try {
            scraperPersistenceService.upsertProductsBulk(new ArrayList<>(deduped.values()));
            System.err.println("[DEBUG] upsertProductsBulk completed WITHOUT throwing exceptions.");
        } catch (Exception e) {
            System.err.println("[DEBUG FATAL] upsertProductsBulk crashed!");
            e.printStackTrace(); // CRITICAL: Exposes database constraint errors (like missing foreign keys or max length exceeded)
        }

        log.info("[Persistence] Persisted {} products for {} ({} duplicates removed)",
                deduped.size(), marketName, dtos.size() - deduped.size());

        System.err.println("[DEBUG] ----- persistAll FINISHED -----");
    }

    private Category resolveCategory(String categoryName, Market market,
                                      Map<String, Category> cache) {
        if (categoryName == null || categoryName.isBlank()) categoryName = "Digər";

        String cacheKey = market.getId() + ":" + categoryName;
        if (cache.containsKey(cacheKey)) return cache.get(cacheKey);

        // Try exact name match first — use findFirst to handle duplicate names gracefully
        Optional<Category> existing = categoryRepository.findFirstByName(categoryName);
        if (existing.isPresent()) {
            cache.put(cacheKey, existing.get());
            return existing.get();
        }

        // Create new — URL is unique per market+category
        String catUrl = "Extracted From PDF:" + market.getId() + ":" + categoryName;
        Category newCat = scraperPersistenceService.upsertCategory(categoryName, catUrl, market);
        cache.put(cacheKey, newCat);
        return newCat;
    }

    /**
     * Deterministic pseudo-link — same product always maps to same row (enables upsert).
     * Format: pdf://<market>/<slugified-name>/<slugified-details>
     */
    private String buildPseudoLink(String market, String name, String details) {
        String link = "pdf://" + slugify(market) + "/" + slugify(name) + "/"
                + (details != null ? slugify(details) : "");
        return link.length() > 990 ? link.substring(0, 990) : link;
    }

    private String slugify(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9\\u0080-\\uFFFF]", "-").replaceAll("-{2,}", "-");
    }
}
