package com.teztap.service.scraper.pdfExtractor;

import com.teztap.service.scraper.pdfExtractor.scraper.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Entry point for all catalogue scrapers.
 *
 * Usage from your scheduler or existing scraper loop:
 *
 *   orchestrator.runAll();              // run every market
 *   orchestrator.run("TamStore");       // run one by name (case-insensitive)
 */
@Service
@RequiredArgsConstructor
public class CatalogueScraperOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CatalogueScraperOrchestrator.class);

    private final TamStoreScraper    tamStoreScraper;
    private final RahatMarketScraper rahatMarketScraper;
    private final ALMarketScraper    alMarketScraper;
    private final AplusMarketScraper aplusMarketScraper;

    public void runAll() {
        log.info("=== CatalogueScraperOrchestrator: start ===");
        allScrapers().forEach(BaseMarketScraper::run);
        log.info("=== CatalogueScraperOrchestrator: done ===");
    }

    public void run(String marketName) {
        Map<String, BaseMarketScraper> byName = allScrapers().stream()
                .collect(Collectors.toMap(
                        s -> s.getMarketName().toLowerCase(),
                        Function.identity()));

        BaseMarketScraper scraper = byName.get(marketName.toLowerCase());
        if (scraper == null) throw new IllegalArgumentException(
                "Unknown market: " + marketName + ". Available: " + byName.keySet());

        scraper.run();
    }

    private List<BaseMarketScraper> allScrapers() {
        return List.of(tamStoreScraper, rahatMarketScraper, alMarketScraper, aplusMarketScraper);
    }
}
