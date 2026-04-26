package com.teztap.service.scraper.pdfExtractor.scraper;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.teztap.service.scraper.PlaywrightManager;
import com.teztap.service.scraper.pdfExtractor.extractor.ClaudeExtractionClient;
import com.teztap.service.scraper.pdfExtractor.extractor.MarkdownTableParser;
import com.teztap.service.scraper.pdfExtractor.extractor.PdfTextExtractor;
import com.teztap.service.scraper.pdfExtractor.matcher.ProductMatcherService;
import com.teztap.service.scraper.pdfExtractor.model.ExtractedProduct;
import com.teztap.service.scraper.pdfExtractor.service.CataloguePersistenceService;
import com.teztap.service.scraper.pdfExtractor.service.ProcessedCatalogueService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scraper for Rahat Market.
 * HTML: <a href="assets/files/rahat_market_xxx.pdf" target="_blank">
 * Links are relative — prepend BASE_URL.
 *
 * Extraction uses the tiered pipeline from BaseMarketScraper.processPdfUrl():
 *   Tier 1 → PDFBox text → Haiku (cheapest)
 *   Tier 2 → rendered images → Haiku vision
 *   Tier 3 → full PDF binary → Sonnet (last resort)
 */
@Component
public class RahatMarketScraper extends BaseMarketScraper {

    private static final String BASE_URL = "https://rahatmarket.az/";

    public RahatMarketScraper(PlaywrightManager playwrightManager,
                              ProductMatcherService matcherService,
                              CataloguePersistenceService persistenceService,
                              ProcessedCatalogueService processedService,
                              ClaudeExtractionClient claude,
                              MarkdownTableParser parser,
                              PdfTextExtractor pdfTextExtractor,
                              ObjectMapper mapper) {
        super(playwrightManager, matcherService, persistenceService, processedService,
                claude, parser, pdfTextExtractor, mapper);
    }

    @Override public String getMarketName()       { return "RAHAT"; }
    @Override public String getCataloguePageUrl() { return "https://rahatmarket.az/az/aksiyalar/rahat-market"; }

    @Override
    protected List<ExtractedProduct> scrapeProducts() throws Exception {
        List<String> pdfUrls = fetchPdfUrls();
        List<ExtractedProduct> all = new ArrayList<>();

        for (String pdfUrl : pdfUrls) {
            if (processedService.isProcessed(pdfUrl)) {
                log.info("[RahatMarket] Already processed: {}", pdfUrl);
                continue;
            }
            log.info("[RahatMarket] Processing: {}", pdfUrl);
            try {
                List<ExtractedProduct> products = processPdfUrl(pdfUrl);
                log.info("[RahatMarket] {} products extracted from {}", products.size(), pdfUrl);
                all.addAll(products);
            } catch (Exception e) {
                log.error("[RahatMarket] Error processing {}: {}", pdfUrl, e.getMessage());
            } finally {
                // Mark processed regardless of outcome — avoids infinite retries on broken PDFs
                processedService.markProcessed(pdfUrl, getMarketName());
            }
        }

        return all;
    }

    private List<String> fetchPdfUrls() {
        List<String> results = new ArrayList<>();
        try (BrowserContext ctx = playwrightManager.newContext()) {
            Page page = newPage(ctx);
            navigateAndWaitFor(page, getCataloguePageUrl(), "a[href$='.pdf']");

            Set<String> seen = new HashSet<>();
            for (ElementHandle a : page.querySelectorAll("a[href$='.pdf']")) {
                String href = a.getAttribute("href");
                if (href == null || href.isBlank()) continue;
                String full = href.startsWith("http") ? href : BASE_URL + href;
                if (seen.add(full)) results.add(full);
            }
        } catch (Exception e) {
            log.error("[RahatMarket] Error fetching PDF links: {}", e.getMessage());
        }
        log.info("[RahatMarket] Found {} PDF(s)", results.size());
        return results;
    }
}
