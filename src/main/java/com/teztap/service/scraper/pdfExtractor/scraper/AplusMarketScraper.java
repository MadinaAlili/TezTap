package com.teztap.service.scraper.pdfExtractor.scraper;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Scraper for Aplus Market.
 *
 * HTML: <div class="actionBoxContainer"><div class="box">
 *         <img src="https://api.aplus.az/storage/HASH.jpg">
 *         <div class="nameContainer"><span>Kataloqu yüklə</span></div>
 *       </div></div>
 *
 * No direct PDF URL — clicking the box triggers a browser download.
 * Processed key = thumbnail image src (stable per catalogue edition).
 *
 * Extraction uses the tiered pipeline from BaseMarketScraper.processPdfPath():
 *   Tier 1 → PDFBox text → Haiku (cheapest)
 *   Tier 2 → rendered images → Haiku vision
 *   Tier 3 → full PDF binary → Sonnet (last resort)
 */
@Component
public class AplusMarketScraper extends BaseMarketScraper {

    private static final int DOWNLOAD_TIMEOUT_MS = 30_000;

    public AplusMarketScraper(PlaywrightManager playwrightManager,
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

    @Override public String getMarketName()       { return "APLUS"; }
    @Override public String getCataloguePageUrl() { return "https://www.aplus.az/action"; }

    @Override
    protected List<ExtractedProduct> scrapeProducts() throws Exception {
        List<ExtractedProduct> all = new ArrayList<>();

        // Single context for the entire run — no repeated browser opens
        try (BrowserContext ctx = playwrightManager.newContext()) {
            Page page = newPage(ctx);
            navigateAndWaitFor(page, getCataloguePageUrl(), "div.actionBoxContainer div.box img");

            // Collect all thumbnail URLs first
            List<String> thumbnailUrls = collectThumbnailUrls(page);
            log.info("[AplusMarket] Found {} catalogue(s)", thumbnailUrls.size());

            for (String thumbnailUrl : thumbnailUrls) {
                if (processedService.isProcessed(thumbnailUrl)) {
                    log.info("[AplusMarket] Already processed: {}", thumbnailUrl);
                    continue;
                }

                log.info("[AplusMarket] Downloading via click: {}", thumbnailUrl);
                Path tmp = null;
                try {
                    // Re-navigate if the page drifted after a previous download
                    if (!page.url().contains("aplus.az/action")) {
                        navigateAndWaitFor(page, getCataloguePageUrl(),
                                "div.actionBoxContainer div.box img");
                    }

                    ElementHandle targetBox = findBoxForThumbnail(page, thumbnailUrl);
                    if (targetBox == null) {
                        log.warn("[AplusMarket] Could not locate box for: {}", thumbnailUrl);
                        continue;
                    }

                    // Register download listener BEFORE clicking
                    CompletableFuture<Download> downloadFuture = new CompletableFuture<>();
                    page.onDownload(downloadFuture::complete);
                    targetBox.click();

                    Download download;
                    try {
                        download = downloadFuture.get(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        log.warn("[AplusMarket] Download timed out for: {}", thumbnailUrl);
                        continue; // markProcessed in finally
                    } catch (ExecutionException e) {
                        log.warn("[AplusMarket] Download failed for {}: {}",
                                thumbnailUrl, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                        continue;
                    }

                    tmp = Files.createTempFile("aplus_", ".pdf");
                    download.saveAs(tmp);

                    if (Files.size(tmp) == 0) {
                        log.warn("[AplusMarket] Empty download for: {}", thumbnailUrl);
                        continue;
                    }

                    List<ExtractedProduct> products = processPdfPath(tmp);
                    log.info("[AplusMarket] {} products from {}", products.size(), thumbnailUrl);
                    all.addAll(products);

                } catch (Exception e) {
                    log.error("[AplusMarket] Error for {}: {}", thumbnailUrl, e.getMessage());
                } finally {
                    deleteSilently(tmp);
                    // Mark processed regardless of outcome — avoids infinite retries on broken catalogues
                    processedService.markProcessed(thumbnailUrl, getMarketName());
                }
            }
        }

        return all;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<String> collectThumbnailUrls(Page page) {
        List<String> urls = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            for (ElementHandle box : page.querySelectorAll("div.actionBoxContainer div.box")) {
                ElementHandle img = box.querySelector("img");
                if (img == null) continue;
                String src = img.getAttribute("src");
                if (src != null && !src.isBlank() && seen.add(src)) urls.add(src);
            }
        } catch (Exception e) {
            log.warn("[AplusMarket] Error collecting thumbnail URLs: {}", e.getMessage());
        }
        return urls;
    }

    private ElementHandle findBoxForThumbnail(Page page, String thumbnailUrl) {
        try {
            for (ElementHandle box : page.querySelectorAll("div.actionBoxContainer div.box")) {
                ElementHandle img = box.querySelector("img");
                if (img != null && thumbnailUrl.equals(img.getAttribute("src"))) {
                    return box;
                }
            }
        } catch (Exception e) {
            log.warn("[AplusMarket] Error finding box for {}: {}", thumbnailUrl, e.getMessage());
        }
        return null;
    }
}
