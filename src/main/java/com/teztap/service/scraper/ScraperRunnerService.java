package com.teztap.service.scraper;

import com.teztap.dto.ProductDTO;
import com.teztap.kafka.EventPublisher;
import com.teztap.kafka.kafkaEventDto.ProductCreatedEvent;
import com.teztap.model.Category;
import com.teztap.model.Product;
import com.teztap.service.scraper.araz.ArazCategoryScraper;
import com.teztap.service.scraper.araz.ArazProductScraper;
import com.teztap.service.scraper.bazarstore.BazarstoreCategoryScraper;
import com.teztap.service.scraper.bazarstore.BazarstoreProductScraper;
import com.teztap.service.scraper.neptun.NeptunCategoryScraper;
import com.teztap.service.scraper.neptun.NeptunProductScraper;
import com.teztap.service.scraper.omid.OmidCategoryScraper;
import com.teztap.service.scraper.omid.OmidProductScraper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class ScraperRunnerService {

    private static final Logger log = LoggerFactory.getLogger(ScraperRunnerService.class);

    private final NeptunCategoryScraper neptunCategoryScraper;
    private final NeptunProductScraper  neptunProductScraper;
    private final ArazCategoryScraper   arazCategoryScraper;
    private final ArazProductScraper    arazProductScraper;
    private final BazarstoreCategoryScraper bazarstoreCategoryScraper;
    private final BazarstoreProductScraper bazarstoreProductScraper;
    private final OmidCategoryScraper omidCategoryScraper;
    private final OmidProductScraper omidProductScraper;

    private final EventPublisher eventPublisher;

    /**
     * WHY fixedDelay instead of fixedRate:
     *
     *   fixedRate fires every N ms regardless of whether the previous run finished.
     *   On a limited EC2, if a scrape cycle takes 4 hours and the rate is 2 hours,
     *   two Chromium instances run simultaneously → OOM kill.
     *
     *   fixedDelay starts the NEXT run only AFTER the current one fully completes.
     *   This guarantees one Chromium instance at a time, no matter how slow the run is.
     *
     * WHY no @Async here:
     *
     *   @Scheduled already runs in its own thread from the task scheduler pool.
     *   Adding @Async on the same method causes Spring's proxy to attempt double-wrapping,
     *   which silently breaks one or both annotations depending on proxy order.
     *   Remove @Async — the schedule runs off the main thread already.
     */
    @Scheduled(initialDelay = 15_000, fixedDelay = 3600 * 24 * 1_000L) // 24 hours between runs
    public void runScrapers() {
        log.info("========== Scrape cycle starting ==========");

//        runMarket("BAZARSTORE",
//                () -> bazarstoreCategoryScraper.scrape("https://bazarstore.az"),
//                bazarstoreProductScraper);
//
//        runMarket("NEPTUN",
//                () -> neptunCategoryScraper.scrape("https://neptun.az"),
//                neptunProductScraper);
//
//        runMarket("ARAZ",
//                () -> arazCategoryScraper.scrape("https://www.arazmarket.az/az"),
//                arazProductScraper);

//        runMarket("OMID",
//                () -> omidCategoryScraper.scrape("https://omid.az"),
//                omidProductScraper);

        log.info("========== Scrape cycle complete ==========");
    }

    /**
     * Runs a full market scrape — categories first, then products per category.
     *
     * Each market is fully isolated:
     *   - A crash inside one market never affects other markets.
     *   - A crash on one category's products never stops the remaining categories.
     *   - Kafka publish failures are logged but do not abort the scrape.
     */
    private void runMarket(String marketName,
                           Supplier<List<Category>> categoryScraper,
                           Scraper<List<Product>> productScraper) {
        log.info("[{}] Starting category scrape", marketName);
        List<Category> categories;

        try {
            categories = categoryScraper.get();
            log.info("[{}] {} categories found", marketName, categories.size());
        } catch (Exception e) {
            log.error("[{}] Category scrape failed — skipping market: {}",
                    marketName, e.getMessage(), e);
            return;
        }

        if (categories.isEmpty()) {
            log.warn("[{}] 0 categories returned — check selectors or site availability",
                    marketName);
            return;
        }

        int total = 0;
        for (Category cat : categories) {
            List<Product> products = List.of();
            try {
                log.info("[{}] Scraping '{}' → {}", marketName, cat.getName(), cat.getUrl());
                products = productScraper.scrape(cat.getUrl());
                total += products.size();
            } catch (Exception e) {
                // Log and continue — one bad category must never stop the rest
                log.error("[{}] Product scrape failed for '{}': {}",
                        marketName, cat.getName(), e.getMessage(), e);
            }

            if (!products.isEmpty()) {
                try {
                    eventPublisher.publish(
                            new ProductCreatedEvent(toDTO(products)));
                } catch (Exception e) {
                    log.error("[{}] Kafka publish failed for '{}': {}",
                            marketName, cat.getName(), e.getMessage());
                    // Do NOT rethrow — a Kafka failure must not abort the scrape loop
                }
            }
        }

        log.info("[{}] Market complete — {} products across {} categories",
                marketName, total, categories.size());
    }

    // ── DTO mapping ───────────────────────────────────────────────────────────

    private List<ProductDTO> toDTO(List<Product> products) {
        return products.stream().map(this::toDTO).toList();
    }

    private ProductDTO toDTO(Product p) {
        return new ProductDTO(
                p.getId(),
                p.getName(),
                p.getOriginalPrice(),
                p.getDiscountPrice(),
                p.getDiscountPercentage(),
                p.getLink(),
                p.getImageUrl(),
                p.getCategory() != null ? p.getCategory().getId() : null,
                p.getMarket()   != null ? p.getMarket().getId()   : null
        );
    }
}