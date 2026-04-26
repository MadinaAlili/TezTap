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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scraper for TamStore.
 *
 * HTML: <a download href="/uploads/TIMESTAMP-HASH.pdf">
 *
 * Not all PDFs are discount catalogues. Classification order (cheapest first):
 *   1. Keyword match on page-1 text (free — no Claude call)
 *   2. Haiku vision on page-1 image (if page 1 has no selectable text)
 *
 * Extraction then follows the tiered pipeline in BaseMarketScraper.processPdfPath().
 */
@Component
public class TamStoreScraper extends BaseMarketScraper {

    /** Azerbaijani keywords that indicate a discount catalogue. */
    private static final Set<String> DISCOUNT_KEYWORDS = Set.of(
            "endirim", "aksiya", "kataloq", "qiymət", "kampaniya", "təklif"
    );

    public TamStoreScraper(PlaywrightManager playwrightManager,
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

    @Override public String getMarketName()       { return "TAMSTORE"; }
    @Override public String getCataloguePageUrl() { return "https://www.tamstore.az/az/kampaniyalar"; }

    @Override
    protected List<ExtractedProduct> scrapeProducts() throws Exception {
        List<String> pdfUrls = fetchPdfUrls();
        List<ExtractedProduct> all = new ArrayList<>();

        for (String pdfUrl : pdfUrls) {
            if (processedService.isProcessed(pdfUrl)) {
                log.info("[TamStore] Already processed: {}", pdfUrl);
                continue;
            }

            Path tmp = null;
            try {
                tmp = Files.createTempFile("tamstore_", ".pdf");
                streamToFile(pdfUrl, tmp);

                if (!isNonEmpty(tmp)) {
                    log.warn("[TamStore] Empty download: {}", pdfUrl);
                    processedService.markProcessed(pdfUrl, getMarketName());
                    continue;
                }

                if (!isDiscountCatalogue(tmp, pdfUrl)) {
                    log.info("[TamStore] Not a discount catalogue — skipping: {}", pdfUrl);
                    processedService.markProcessed(pdfUrl, getMarketName());
                    continue;
                }

                log.info("[TamStore] Extracting: {}", pdfUrl);
                List<ExtractedProduct> products = processPdfPath(tmp);
                log.info("[TamStore] {} products extracted from {}", products.size(), pdfUrl);
                all.addAll(products);

            } catch (Exception e) {
                log.error("[TamStore] Error processing {}: {}", pdfUrl, e.getMessage());
                // Fall through to markProcessed — avoids infinite retries on broken PDFs
            } finally {
                deleteSilently(tmp);
                processedService.markProcessed(pdfUrl, getMarketName());
            }
        }

        return all;
    }

    // ── Catalogue classifier ──────────────────────────────────────────────────

    /**
     * Returns true if the PDF is a discount catalogue, false otherwise.
     *
     * Strategy (cheapest first):
     *   Step 1: keyword match on page-1 text — zero API cost
     *   Step 2: Haiku vision on page-1 image — only if step 1 is inconclusive
     *
     * On any unexpected error, defaults to TRUE so we don't silently drop valid catalogues.
     */
    private boolean isDiscountCatalogue(Path pdfPath, String pdfUrl) {
        // Step 1: try keyword match on page-1 text (free)
        try {
            String page1Text = pdfTextExtractor.extractPage1Text(pdfPath);
            if (page1Text != null && page1Text.strip().length() > 50) {
                String lower = page1Text.toLowerCase();
                boolean matched = DISCOUNT_KEYWORDS.stream().anyMatch(lower::contains);
                log.debug("[TamStore] Keyword classify '{}': {}", pdfUrl, matched);
                return matched;
            }
            log.debug("[TamStore] Page-1 text too short ({} chars) — falling back to vision classify",
                    page1Text == null ? 0 : page1Text.strip().length());
        } catch (Exception e) {
            log.warn("[TamStore] Keyword classify failed for {}: {} — falling back to vision",
                    pdfUrl, e.getMessage());
        }

        // Step 2: render page 1 → Haiku vision (only for truly scanned first pages)
        try {
            byte[] page1Image = pdfTextExtractor.renderPage1Optimized(pdfPath);
            if (page1Image == null || page1Image.length == 0) {
                log.warn("[TamStore] Could not render page 1 for {} — defaulting to true", pdfUrl);
                return true; // fail-open
            }
            boolean result = claude.checkIsDiscountCatalogueVision(page1Image);
            log.info("[TamStore] Vision classify '{}': {}", pdfUrl, result);
            return result;
        } catch (Exception e) {
            log.error("[TamStore] Vision classify failed for {}: {} — defaulting to true",
                    pdfUrl, e.getMessage());
            return true; // fail-open
        }
    }

    // ── URL discovery ─────────────────────────────────────────────────────────

    private List<String> fetchPdfUrls() {
        List<String> results = new ArrayList<>();
        try (BrowserContext ctx = playwrightManager.newContext()) {
            Page page = newPage(ctx);
            navigateAndWaitFor(page, getCataloguePageUrl(), "a[download][href$='.pdf']");

            Set<String> seen = new HashSet<>();
            for (ElementHandle a : page.querySelectorAll("a[download][href$='.pdf']")) {
                String href = a.getAttribute("href");
                if (href == null || href.isBlank()) continue;
                String full = href.startsWith("http") ? href : "https://www.tamstore.az" + href;
                if (seen.add(full)) results.add(full);
            }
        } catch (Exception e) {
            log.error("[TamStore] Error fetching PDF links: {}", e.getMessage());
        }
        log.info("[TamStore] Found {} PDF(s)", results.size());
        return results;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isNonEmpty(Path path) {
        try { return path != null && Files.exists(path) && Files.size(path) > 0; }
        catch (Exception e) { return false; }
    }
}
