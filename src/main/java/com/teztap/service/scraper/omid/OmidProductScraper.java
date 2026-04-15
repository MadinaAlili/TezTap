package com.teztap.service.scraper.omid;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import com.teztap.model.Category;
import com.teztap.model.Market;
import com.teztap.model.Product;
import com.teztap.repository.CategoryRepository;
import com.teztap.repository.MarketRepository;
import com.teztap.service.scraper.PlaywrightManager;
import com.teztap.service.scraper.Scraper;
import com.teztap.service.scraper.ScraperPersistenceService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OmidProductScraper implements Scraper<List<Product>> {

    private static final Logger log = LoggerFactory.getLogger(OmidProductScraper.class);

    private static final String BASE_URL    = "https://omid.az";
    private static final String MARKET_NAME = "OMID";
    private static final int    NAV_TIMEOUT = 30_000;
    private static final int    MAX_PAGES   = 100;
    private static final long   PAGE_DELAY  = 1_000;

    private final PlaywrightManager         playwrightManager;
    private final ScraperPersistenceService persistence;
    private final CategoryRepository        categoryRepository;
    private final MarketRepository          marketRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // PRICE STRUCTURE (omid.az custom Shopify theme):
    //
    // Each product card embeds a <script class="data-json-product" type="application/json">
    // tag containing the full Shopify product JSON. This is the most reliable
    // data source — it never has parsing ambiguity from currency symbols or SVG.
    //
    // The JSON contains:
    //   "variants[0].price"           — current selling price in QƏPIK (cents)
    //   "variants[0].compare_at_price" — original price in QƏPIK, or null
    //   "name"                         — product name (from first variant)
    //   "handle"                       — URL slug
    //   "images[0].src"               — protocol-relative image URL
    //
    // Price conversion: divide integer by 100 to get AZN.
    //   price: 310  → 3.10 ₼
    //   price: 3590, compare_at_price: 5250 → discounted from 52.50 ₼ to 35.90 ₼
    //
    // WHY JSON parsing over DOM text:
    //   span[data-js-product-price] contains rendered HTML like "3.10 ₼" or
    //   nested <strong>/<span> for sale prices. The embedded JSON is always
    //   clean numeric integers, immune to rendering differences.
    //
    // PAGINATION:
    //   Omid.az uses ?page=N pagination with no filter query params on standard
    //   collection pages. The pagination nav is always rendered.
    //   URL format: /collections/slug?page=2
    //
    // PRODUCT LINK:
    //   Built from handle: BASE_URL + /products/ + handle
    //   We avoid using the collection-scoped link (/collections/x/products/y)
    //   and prefer the canonical /products/handle URL for consistency.
    // -------------------------------------------------------------------------

    @Override
    public List<Product> scrape(String categoryUrl) {
        Market   market   = marketRepository.findByName(MARKET_NAME).get();
        Category category = categoryRepository.findByUrl(categoryUrl).orElse(null);
        List<Product> result = new ArrayList<>();

        try (BrowserContext ctx = playwrightManager.newContext()) {
            Page page = ctx.newPage();

            ctx.route("**/*.{png,jpg,jpeg,gif,webp,ico,woff,woff2,ttf,otf}",
                    route -> route.abort());

            if (!navigateWithRetry(page, categoryUrl, 3)) {
                log.error("[Omid-Prod] Cannot load {} — skipping", categoryUrl);
                return result;
            }

            // Dismiss any popups on first load
            dismissPopups(page);

            if (!waitForGrid(page)) {
                log.info("[Omid-Prod] Empty category: {}", categoryUrl);
                return result;
            }

            // Read canonical URL AFTER page settles — Omid may redirect
            String resolvedBase = stripPageParam(page.url());
            log.info("[Omid-Prod] Base URL: {}", resolvedBase);

            // Read total page count from the visible pagination nav
            int totalPages = getTotalPages(page);
            log.info("[Omid-Prod] Total pages: {}", totalPages);

            for (int pageNum = 1; pageNum <= Math.min(totalPages, MAX_PAGES); pageNum++) {

                if (pageNum > 1) {
                    String pagedUrl = appendPageParam(resolvedBase, pageNum);
                    log.info("[Omid-Prod] Page {}/{}: {}", pageNum, totalPages, pagedUrl);
                    if (!navigateWithRetry(page, pagedUrl, 3)) {
                        log.warn("[Omid-Prod] Cannot load page {} — stopping", pageNum);
                        break;
                    }
                    if (!waitForGrid(page)) {
                        log.info("[Omid-Prod] No grid on page {} — stopping", pageNum);
                        break;
                    }
                }

                // Cards are server-rendered — no scroll needed
                List<ElementHandle> cards = page.querySelectorAll(
                        "div#product-grid div.grid__item.product-item");
                log.info("[Omid-Prod] {} cards on page {}", cards.size(), pageNum);

                if (cards.isEmpty()) {
                    log.info("[Omid-Prod] No cards on page {} — done", pageNum);
                    break;
                }

                for (ElementHandle card : cards) {
                    try {
                        Product p = parseCard(card, category, market);
                        if (p != null) result.add(p);
                    } catch (Exception e) {
                        log.warn("[Omid-Prod] Skipping card: {}", e.getMessage());
                    }
                }

                sleep(PAGE_DELAY);
            }

        } catch (Exception e) {
            log.error("[Omid-Prod] Scrape failed for {}: {}", categoryUrl, e.getMessage(), e);
        }

        log.info("[Omid-Prod] Done — {} products for {}", result.size(), categoryUrl);
        return result;
    }

    // ── Card parsing ──────────────────────────────────────────────────────────

    /**
     * Parses a product card by reading the embedded JSON data script tag.
     *
     * The <script class="data-json-product" type="application/json"> element
     * contains the complete Shopify product object — reliably structured,
     * no rendering artifacts, no currency symbols to strip.
     */
    private Product parseCard(ElementHandle card, Category category, Market market) {

        // Read the embedded product JSON
        ElementHandle jsonScript = card.querySelector("script.data-json-product");
        if (jsonScript == null) {
            log.debug("[Omid-Prod] No data-json-product script found");
            return null;
        }

        String jsonText = jsonScript.innerHTML().trim();
        if (isBlank(jsonText)) return null;

        JsonNode json;
        try {
            json = objectMapper.readTree(jsonText);
        } catch (Exception e) {
            log.warn("[Omid-Prod] JSON parse failed: {}", e.getMessage());
            return null;
        }

        // ── Name ──────────────────────────────────────────────────────────────
        // Use the first variant's "name" field which is the product title.
        // The top-level "handle" field is the URL slug.
        JsonNode variantsNode = json.path("variants");
        if (!variantsNode.isArray() || variantsNode.isEmpty()) return null;

        JsonNode firstVariant = variantsNode.get(0);
        String name = firstVariant.path("name").asText("").trim();
        if (isBlank(name)) {
            // Fallback to product handle converted to title
            name = json.path("handle").asText("").replace("-", " ").toUpperCase().trim();
        }
        if (isBlank(name)) {
            log.debug("[Omid-Prod] Card has no name — skipped");
            return null;
        }

        // ── Link ──────────────────────────────────────────────────────────────
        // Canonical product URL: /products/{handle}
        // Avoids the collection-scoped variant (/collections/x/products/y)
        // which would be invalidated if we change which category we scrape from.
        String handle = json.path("handle").asText("").trim();
        if (isBlank(handle)) {
            log.debug("[Omid-Prod] Card '{}' has no handle — skipped", name);
            return null;
        }
        String link = BASE_URL + "/products/" + handle;

        // ── Prices ────────────────────────────────────────────────────────────
        //
        // Shopify stores prices as integers in the smallest currency unit (qəpik).
        //   price: 310          → 3.10 ₼  (current selling price)
        //   compare_at_price: null → no discount
        //   compare_at_price: 5250 → 52.50 ₼ (original price before discount)
        //
        // A null compare_at_price means no discount.
        // compare_at_price == price means no real discount (Shopify allows this).
        // We only treat it as discounted when compare_at_price > price.
        //
        long priceQepik = firstVariant.path("price").asLong(0);
        if (priceQepik <= 0) {
            log.debug("[Omid-Prod] Zero price for '{}' — skipped", name);
            return null;
        }

        BigDecimal currentPrice = BigDecimal.valueOf(priceQepik, 2); // divide by 100

        BigDecimal originalPrice;
        BigDecimal discountPrice = null;
        BigDecimal discountPct   = null;

        JsonNode compareNode = firstVariant.path("compare_at_price");
        boolean hasCompare = !compareNode.isNull() && !compareNode.isMissingNode();

        if (hasCompare) {
            long compareQepik = compareNode.asLong(0);
            if (compareQepik > priceQepik) {
                BigDecimal comparePrice = BigDecimal.valueOf(compareQepik, 2);
                originalPrice = comparePrice;
                discountPrice = currentPrice;
                discountPct   = comparePrice.subtract(currentPrice)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(comparePrice, 0, RoundingMode.HALF_UP);
            } else {
                // compare_at_price exists but <= price — treat as no discount
                originalPrice = currentPrice;
            }
        } else {
            originalPrice = currentPrice;
        }

        // ── Image ─────────────────────────────────────────────────────────────
        // Images array from JSON: [{"id":..., "src":"//omid.az/cdn/shop/files/..."}]
        // Protocol-relative URL → normalise to https:
        String imageUrl = extractImageFromJson(json);

        return persistence.upsertProduct(
                name, link, originalPrice, discountPrice, discountPct,
                imageUrl, category, market);
    }

    // ── Image extraction ──────────────────────────────────────────────────────

    /**
     * Extracts and normalises the product image URL from the JSON data.
     *
     * The JSON images array contains protocol-relative URLs: //omid.az/cdn/shop/files/...
     * We use the first image and prepend "https:" to make it absolute.
     */
    private String extractImageFromJson(JsonNode json) {
        try {
            JsonNode images = json.path("images");
            if (!images.isArray() || images.isEmpty()) return "";

            String src = images.get(0).path("src").asText("").trim();
            if (src.isBlank()) return "";

            if (src.startsWith("//")) return "https:" + src;
            if (src.startsWith("http")) return src;
            return BASE_URL + src;
        } catch (Exception e) {
            return "";
        }
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    /**
     * Reads the total number of pages from the Shopify pagination nav.
     *
     * Omid.az renders:
     *   ul.pagination__list.page-numbers
     *     li > span (current page, no link)
     *     li > a[href*="page=2"]
     *     li > a[href*="page=7"]  ← last page link
     *     li > a.page-next (next arrow, skip)
     *
     * We find the maximum page number from all pagination links,
     * ignoring the arrow navigation link.
     *
     * Returns 1 as safe fallback when pagination is absent.
     */
    private int getTotalPages(Page page) {
        try {
            Object result = page.evaluate("""
                () => {
                    const links = document.querySelectorAll(
                        'ul.pagination__list a.pagination__item[href*="page="]');
                    let max = 1;
                    for (const a of links) {
                        // Skip the arrow navigation link
                        if (a.classList.contains('page-next') ||
                            a.classList.contains('pagination__item-arrow')) continue;
                        const m = a.href.match(/[?&]page=(\\d+)/);
                        if (m) max = Math.max(max, parseInt(m[1], 10));
                    }
                    return max;
                }
            """);
            if (result instanceof Number) return ((Number) result).intValue();
            return 1;
        } catch (Exception e) {
            log.debug("[Omid-Prod] Could not read pagination: {}", e.getMessage());
            return 1;
        }
    }

    // ── Page helpers ──────────────────────────────────────────────────────────

    /**
     * Waits for at least one product card to be present in the grid.
     * Returns false for empty categories — a valid stop condition.
     */
    private boolean waitForGrid(Page page) {
        try {
            page.waitForSelector("div#product-grid div.grid__item.product-item",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.ATTACHED)
                            .setTimeout(12_000));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Popup dismissal — currently no popups on omid.az but ready for future ones.
     * Tries common Shopify popup patterns silently.
     */
    private void dismissPopups(Page page) {
        String[] popupSelectors = {
                "button.popup-close",
                "button.modal__close",
                "[data-remodal-action='close']",
                ".klaviyo-close-form",
                "#popup-modal button.close"
        };
        for (String selector : popupSelectors) {
            try {
                page.waitForSelector(selector,
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(1_500));
                page.click(selector, new Page.ClickOptions().setTimeout(1_000));
                log.info("[Omid-Prod] Dismissed popup: {}", selector);
                break;
            } catch (Exception ignored) {}
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private boolean navigateWithRetry(Page page, String url, int maxAttempts) {
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(NAV_TIMEOUT));
                return true;
            } catch (Exception e) {
                log.warn("[Omid-Prod] Navigate attempt {}/{}: {}", i, maxAttempts, e.getMessage());
                if (i < maxAttempts) sleep((long) Math.pow(2, i) * 1_000);
            }
        }
        return false;
    }

    /**
     * Strips any existing page= parameter.
     * Omid collection URLs are clean: /collections/slug (no filter params).
     * After stripping page= the base URL has no query string.
     */
    private String stripPageParam(String url) {
        return url.replaceAll("[&?]page=\\d+", "").replaceAll("[?&]$", "");
    }

    /**
     * Appends ?page=N or &page=N depending on whether a query string exists.
     * Standard Omid collection URLs have no query params so we use ?.
     */
    private String appendPageParam(String base, int pageNum) {
        if (pageNum == 1) return base;
        return base.contains("?") ? base + "&page=" + pageNum
                : base + "?page=" + pageNum;
    }

    // ── Value helpers ─────────────────────────────────────────────────────────

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
