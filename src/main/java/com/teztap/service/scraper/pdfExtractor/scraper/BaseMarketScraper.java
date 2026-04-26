package com.teztap.service.scraper.pdfExtractor.scraper;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.teztap.service.scraper.PlaywrightManager;
import com.teztap.service.scraper.pdfExtractor.extractor.ClaudeExtractionClient;
import com.teztap.service.scraper.pdfExtractor.extractor.MarkdownTableParser;
import com.teztap.service.scraper.pdfExtractor.extractor.PdfTextExtractor;
import com.teztap.service.scraper.pdfExtractor.matcher.ProductMatcherService;
import com.teztap.service.scraper.pdfExtractor.model.ExtractedProduct;
import com.teztap.service.scraper.pdfExtractor.service.CataloguePersistenceService;
import com.teztap.service.scraper.pdfExtractor.service.ProcessedCatalogueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class BaseMarketScraper {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final PlaywrightManager          playwrightManager;
    protected final ProductMatcherService       matcherService;
    protected final CataloguePersistenceService persistenceService;
    protected final ProcessedCatalogueService   processedService;
    protected final ClaudeExtractionClient      claude;
    protected final MarkdownTableParser         parser;
    protected final PdfTextExtractor            pdfTextExtractor;
    protected final ObjectMapper                json;

    @Value("${scraper.export-json:false}")
    private boolean exportJson;

    @Value("${scraper.output-dir:output-json}")
    private String outputDir;

    protected BaseMarketScraper(PlaywrightManager playwrightManager,
                                ProductMatcherService matcherService,
                                CataloguePersistenceService persistenceService,
                                ProcessedCatalogueService processedService,
                                ClaudeExtractionClient claude,
                                MarkdownTableParser parser,
                                PdfTextExtractor pdfTextExtractor,
                                ObjectMapper mapper) {
        this.playwrightManager  = playwrightManager;
        this.matcherService     = matcherService;
        this.persistenceService = persistenceService;
        this.processedService   = processedService;
        this.claude             = claude;
        this.parser             = parser;
        this.pdfTextExtractor   = pdfTextExtractor;
        this.json               = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    }

    public abstract String getMarketName();
    public abstract String getCataloguePageUrl();
    protected abstract List<ExtractedProduct> scrapeProducts() throws Exception;

    // ── Entry point ───────────────────────────────────────────────────────────

    public void run() {
        log.info("=== {} scraper started ===", getMarketName());
        try {
            List<ExtractedProduct> products = scrapeProducts();
            if (products == null || products.isEmpty()) {
                log.info("[{}] No new products.", getMarketName());
                return;
            }
            log.info("[{}] Extracted {} products", getMarketName(), products.size());
            matcherService.matchAll(products);
            if (exportJson) exportToJson(products);
            persistenceService.persistAll(products, getMarketName());
        } catch (Exception e) {
            log.error("[{}] Scraper failed: {}", getMarketName(), e.getMessage(), e);
        }
        log.info("=== {} scraper finished ===", getMarketName());
    }

    // ── Tiered PDF processor ──────────────────────────────────────────────────

    /**
     * Tiered extraction pipeline for a single PDF URL:
     *
     *   Tier 1 — FREE:   PDFBox text extraction → Haiku text mode
     *   Tier 2 — CHEAP:  Render pages → Haiku vision (72 dpi, grayscale, JPEG 75)
     *   Tier 3 — LEGACY: Full PDF binary → Sonnet (last resort, most expensive)
     *
     * Returns empty list (never null) on any unrecoverable error.
     * Temp file is always cleaned up in the finally block.
     */
    protected List<ExtractedProduct> processPdfUrl(String pdfUrl) {
        if (pdfUrl == null || pdfUrl.isBlank()) return Collections.emptyList();

        Path tmp = null;
        try {
            tmp = Files.createTempFile(getMarketName().toLowerCase() + "_", ".pdf");
            streamToFile(pdfUrl, tmp);

            if (!isNonEmpty(tmp)) {
                log.warn("[{}] Downloaded empty file: {}", getMarketName(), pdfUrl);
                return Collections.emptyList();
            }

            return processPdfPath(tmp);

        } catch (Exception e) {
            log.error("[{}] Error processing PDF {}: {}", getMarketName(), pdfUrl, e.getMessage());
            return Collections.emptyList();
        } finally {
            deleteSilently(tmp);
        }
    }

    /**
     * Same tiered pipeline but for a PDF already on disk (e.g. Aplus download).
     * Does NOT delete the file — caller is responsible.
     * Returns empty list (never null) on any error.
     */
    protected List<ExtractedProduct> processPdfPath(Path pdfPath) {
        if (pdfPath == null || !isNonEmpty(pdfPath)) return Collections.emptyList();

        // ── Tier 1: free text extraction ──────────────────────────────────────
        try {
            String text = pdfTextExtractor.extractText(pdfPath);
            if (!text.isBlank()) {
                log.info("[{}] Tier 1 — text-based PDF (Haiku text mode)", getMarketName());
                String markdown = claude.extractFromText(text);
                if (!markdown.isBlank()) {
                    List<ExtractedProduct> products = parser.parse(markdown);
                    if (!products.isEmpty()) return products;
                    log.warn("[{}] Tier 1 parsing returned 0 products — falling through", getMarketName());
                }
            }
        } catch (Exception e) {
            log.warn("[{}] Tier 1 failed: {} — falling through to Tier 2", getMarketName(), e.getMessage());
        }

        // ── Tier 2: render pages → Haiku vision ───────────────────────────────
        try {
            log.info("[{}] Tier 2 — scanned PDF (Haiku vision, 72 dpi, grayscale)", getMarketName());
            List<byte[]> pages = pdfTextExtractor.renderPagesOptimized(pdfPath);
            if (!pages.isEmpty()) {
                String markdown = claude.extractFromImages(pages);
                if (!markdown.isBlank()) {
                    List<ExtractedProduct> products = parser.parse(markdown);
                    if (!products.isEmpty()) return products;
                    log.warn("[{}] Tier 2 parsing returned 0 products — falling through", getMarketName());
                }
            }
        } catch (Exception e) {
            log.warn("[{}] Tier 2 failed: {} — falling through to Tier 3", getMarketName(), e.getMessage());
        }

        // ── Tier 3: legacy Sonnet + full PDF binary (most expensive) ──────────
        try {
            log.warn("[{}] Tier 3 — legacy Sonnet+PDF (expensive fallback)", getMarketName());
            String markdown = claude.extractFromPdf(pdfPath);
            return parser.parse(markdown);
        } catch (Exception e) {
            log.error("[{}] Tier 3 also failed: {}", getMarketName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Playwright helpers ────────────────────────────────────────────────────

    protected Page newPage(BrowserContext ctx) {
        return ctx.newPage();
    }

    /**
     * Navigates and waits for a CSS selector instead of a fixed sleep.
     * Falls back gracefully if the selector isn't found within the timeout.
     */
    protected void navigateAndWaitFor(Page page, String url, String waitForSelector) {
        try {
            page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(30_000)
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
        } catch (Exception e) {
            log.warn("[{}] Navigation to {} had an issue: {}", getMarketName(), url, e.getMessage());
        }
        try {
            page.waitForSelector(waitForSelector,
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
        } catch (Exception e) {
            log.warn("[{}] Selector '{}' not found on {} — proceeding anyway",
                    getMarketName(), waitForSelector, url);
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    /**
     * Streams URL content directly to a file — never allocates the full byte[] in heap.
     * Throws IOException on non-200 response so callers know the download failed.
     */
    protected void streamToFile(String urlStr, Path dest) throws IOException {
        HttpURLConnection conn = openConnection(urlStr);
        int status = conn.getResponseCode();
        if (status != 200) throw new IOException("HTTP " + status + " for: " + urlStr);
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Downloads small resources (images) into memory.
     * Returns null on any failure — callers must null-check.
     * Use only for images, never for PDFs (use streamToFile for PDFs).
     */
    protected byte[] downloadImageBytes(String urlStr) {
        try {
            HttpURLConnection conn = openConnection(urlStr);
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            log.warn("[{}] Image download failed {}: {}", getMarketName(), urlStr, e.getMessage());
            return null;
        }
    }

    protected boolean urlExists(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    protected void deleteSilently(Path path) {
        if (path != null) {
            try { Files.deleteIfExists(path); } catch (Exception ignored) {}
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private HttpURLConnection openConnection(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setRequestProperty("Referer", getCataloguePageUrl());
        return conn;
    }

    private boolean isNonEmpty(Path path) {
        try { return path != null && Files.exists(path) && Files.size(path) > 0; }
        catch (Exception e) { return false; }
    }

    // ── JSON export ───────────────────────────────────────────────────────────

    private void exportToJson(List<ExtractedProduct> products) {
        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            String ts      = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path   outFile = dir.resolve(getMarketName().toLowerCase() + "_" + ts + ".json");
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("market",        getMarketName());
            wrapper.put("extractedAt",   ts);
            wrapper.put("totalProducts", products.size());
            wrapper.put("products",      products);
            json.writeValue(outFile.toFile(), wrapper);
            log.info("[{}] JSON exported: {}", getMarketName(), outFile.toAbsolutePath());
        } catch (Exception e) {
            log.warn("[{}] JSON export failed: {}", getMarketName(), e.getMessage());
        }
    }
}
