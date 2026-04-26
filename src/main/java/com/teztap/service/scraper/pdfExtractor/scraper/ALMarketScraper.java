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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scraper for AL Market (image-based flipbook catalogue).
 *
 * HTML: <a class="katalog-card" href="/az/kataloq/SLUG">
 *         <img src="/files/catalogs/HASH.jpg">
 *       </a>
 *
 * Each catalogue page is a JPEG image — extractFromImage() (Haiku) is used.
 * No PDF involved here, so PdfTextExtractor is not used for extraction,
 * only the base class injection is satisfied.
 *
 * Processed key = full catalogue page URL (slug is stable per edition).
 */
@Component
public class ALMarketScraper extends BaseMarketScraper {

    private static final String BASE_URL       = "https://almarket.az";
    private static final int    IMAGE_DELAY_MS = 5_000;

    public ALMarketScraper(PlaywrightManager playwrightManager,
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

    @Override public String getMarketName()       { return "ALMARKET"; }
    @Override public String getCataloguePageUrl() { return "https://almarket.az/az/kataloqlar"; }

    @Override
    protected List<ExtractedProduct> scrapeProducts() throws Exception {
        List<String> catalogueUrls = fetchCatalogueUrls();
        List<ExtractedProduct> all = new ArrayList<>();

        for (String catUrl : catalogueUrls) {
            if (processedService.isProcessed(catUrl)) {
                log.info("[ALMarket] Already processed: {}", catUrl);
                continue;
            }

            log.info("[ALMarket] Processing: {}", catUrl);
            try {
                List<String> imageUrls = fetchCatalogueImages(catUrl);
                if (imageUrls.isEmpty()) {
                    log.warn("[ALMarket] No images found in: {}", catUrl);
                    processedService.markProcessed(catUrl, getMarketName());
                    continue;
                }

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < imageUrls.size(); i++) {
                    String imgUrl = imageUrls.get(i);
                    log.info("[ALMarket] Image {}/{}: {}", i + 1, imageUrls.size(), imgUrl);

                    byte[] bytes = downloadImageBytes(imgUrl);
                    if (bytes == null || bytes.length == 0) {
                        log.warn("[ALMarket] Could not download image: {}", imgUrl);
                        continue;
                    }

                    // extractFromImage uses Haiku internally
                    String rows = claude.extractFromImage(bytes, "image/jpeg");
                    if (rows != null && !rows.isBlank()) sb.append(rows).append("\n");

                    if (i < imageUrls.size() - 1) {
                        Thread.sleep(IMAGE_DELAY_MS);
                    }
                }

                List<ExtractedProduct> products = parser.parse(sb.toString());
                log.info("[ALMarket] {} products from {}", products.size(), catUrl);
                all.addAll(products);

            } catch (Exception e) {
                log.error("[ALMarket] Error processing {}: {}", catUrl, e.getMessage());
            } finally {
                // Mark processed even on error — prevents retrying permanently broken catalogues
                processedService.markProcessed(catUrl, getMarketName());
            }
        }

        return all;
    }

    // ── URL discovery ─────────────────────────────────────────────────────────

    private List<String> fetchCatalogueUrls() {
        List<String> results = new ArrayList<>();
        try (BrowserContext ctx = playwrightManager.newContext()) {
            Page page = newPage(ctx);
            navigateAndWaitFor(page, getCataloguePageUrl(), "a.katalog-card");

            Set<String> seen = new HashSet<>();
            for (ElementHandle a : page.querySelectorAll("a.katalog-card[href*='/az/kataloq/']")) {
                String href = a.getAttribute("href");
                if (href == null || href.isBlank()) continue;
                String full = href.startsWith("http") ? href : BASE_URL + href;
                if (seen.add(full)) results.add(full);
            }
        } catch (Exception e) {
            log.error("[ALMarket] Error fetching catalogue list: {}", e.getMessage());
        }
        log.info("[ALMarket] Found {} catalogue(s)", results.size());
        return results;
    }

    private List<String> fetchCatalogueImages(String catUrl) {
        List<String> results = new ArrayList<>();
        try (BrowserContext ctx = playwrightManager.newContext()) {
            Page page = newPage(ctx);
            navigateAndWaitFor(page, catUrl,
                    "img[data-file*='/files/catalogs/'], .list-kataloq img");

            Set<String> seen = new HashSet<>();

            // Primary: data-file attribute
            for (ElementHandle img : page.querySelectorAll("img[data-file*='/files/catalogs/']")) {
                String src = img.getAttribute("data-file");
                if (src == null) src = img.getAttribute("src");
                if (src != null && !src.isBlank() && seen.add(src)) {
                    results.add(src.startsWith("http") ? src : BASE_URL + src);
                }
            }

            // Fallback: regex on raw page HTML
            if (results.isEmpty()) {
                Matcher m = Pattern.compile("/files/catalogs/[a-zA-Z0-9_.-]+\\.jpg")
                        .matcher(page.content());
                while (m.find()) {
                    String url = BASE_URL + m.group();
                    if (seen.add(url)) results.add(url);
                }
            }
        } catch (Exception e) {
            log.error("[ALMarket] Error fetching images from {}: {}", catUrl, e.getMessage());
        }
        log.info("[ALMarket] Found {} image(s) in {}", results.size(), catUrl);
        return results;
    }
}
