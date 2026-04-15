package com.teztap.service.scraper.bazarstore;

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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class BazarstoreProductScraper implements Scraper<List<Product>> {

    private static final Logger log = LoggerFactory.getLogger(BazarstoreProductScraper.class);

    private static final String BASE_URL    = "https://bazarstore.az";
    private static final String MARKET_NAME = "BAZARSTORE";
    private static final int    NAV_TIMEOUT = 30_000;
    private static final int    MAX_PAGES   = 50;
    private static final long   PAGE_DELAY  = 1_000;

    private final PlaywrightManager         playwrightManager;
    private final ScraperPersistenceService persistence;
    private final CategoryRepository        categoryRepository;
    private final MarketRepository          marketRepository;

    // -------------------------------------------------------------------------
    // PRICE STRUCTURE (Shopify Dawn theme with custom modifications):
    //
    // Normal product:
    //   form[datalayer-price="X.XX"]             ← current selling price
    //   (no s.adjusted_compare element)
    //   → originalPrice = datalayer-price, discountPrice = null
    //
    // Discounted product:
    //   form[datalayer-price="X.XX"]             ← discounted selling price
    //   s.price-item--regular.adjusted_compare   ← original/compare price
    //   → originalPrice = adjusted_compare, discountPrice = datalayer-price
    //
    // WHY datalayer-price instead of span.price-item--sale:
    //   The price spans contain currency symbols and potential SVG text that
    //   complicates parsing. datalayer-price is a clean numeric string added
    //   by the GA4 datalayer — it is reliably "X.XX" format, no symbols.
    //
    // IMAGE:
    //   Bazarstore uses Shopify CDN. Images use protocol-relative URLs:
    //   //bazarstore.az/cdn/shop/products/XXXXX_533x.jpg
    //   We convert to https: and use the _533x variant (moderate resolution).
    //
    // PAGINATION:
    //   Shopify renders a hidden pagination nav (display:none) alongside the
    //   infinite scroll widget. We read total page count from this nav via JS.
    //   This is more reliable than speculatively navigating to page N+1 because
    //   it avoids an extra round-trip at the end of every category.
    //
    //   The URL already contains filter params. We strip any existing page=N
    //   and append &page=N since a query string always exists for these URLs.
    // -------------------------------------------------------------------------

    @Override
    public List<Product> scrape(String categoryUrl) {
        Market   market   = marketRepository.findByName(MARKET_NAME).get();
        Category category = categoryRepository.findByUrl(categoryUrl).orElse(null);
        List<Product> result = new ArrayList<>();

        try (BrowserContext ctx = playwrightManager.newContext()) {
            Page page = ctx.newPage();

            // Block media — product data is in HTML, images load from CDN
            ctx.route("**/*.{png,jpg,jpeg,gif,webp,ico,woff,woff2,ttf,otf}",
                    route -> route.abort());

            if (!navigateWithRetry(page, categoryUrl, 3)) {
                log.error("[Bazar-Prod] Cannot load {} — skipping", categoryUrl);
                return result;
            }

            // Dismiss popups on first page only (they appear on initial load)
            dismissPopups(page);

            // Wait for the product grid to be present
            boolean hasProducts = waitForGrid(page);
            if (!hasProducts) {
                log.info("[Bazar-Prod] Empty category: {}", categoryUrl);
                return result;
            }

            // Capture canonical URL AFTER any Shopify redirects.
            // Shopify may redirect /collections/slug → /collections/slug?filter.v.availability=1
            // We MUST read URL after waiting for the grid (page is stable by then).
            String resolvedBase = stripPageParam(page.url());
            log.info("[Bazar-Prod] Base URL: {}", resolvedBase);

            // Read total pages from hidden pagination — avoids speculative navigation
            int totalPages = getTotalPages(page);
            log.info("[Bazar-Prod] Total pages: {}", totalPages);

            for (int pageNum = 1; pageNum <= Math.min(totalPages, MAX_PAGES); pageNum++) {

                if (pageNum > 1) {
                    String pagedUrl = appendPageParam(resolvedBase, pageNum);
                    log.info("[Bazar-Prod] Page {}/{}: {}", pageNum, totalPages, pagedUrl);
                    if (!navigateWithRetry(page, pagedUrl, 3)) {
                        log.warn("[Bazar-Prod] Cannot load page {} — stopping", pageNum);
                        break;
                    }
                    if (!waitForGrid(page)) {
                        log.info("[Bazar-Prod] No grid on page {} — stopping", pageNum);
                        break;
                    }
                    // Re-read total pages on page 2+ in case filters changed totals
                    if (pageNum == 2) {
                        totalPages = getTotalPages(page);
                    }
                }

                // Query cards AFTER page is stable (no scroll needed — Shopify paginates, not lazy-loads on grid)
                List<ElementHandle> cards = page.querySelectorAll("ul#product-grid li.grid__item");
                log.info("[Bazar-Prod] {} cards on page {}", cards.size(), pageNum);

                if (cards.isEmpty()) {
                    log.info("[Bazar-Prod] No cards on page {} — done", pageNum);
                    break;
                }

                for (ElementHandle card : cards) {
                    try {
                        Product p = parseCard(card, category, market);
                        if (p != null) result.add(p);
                    } catch (Exception e) {
                        log.warn("[Bazar-Prod] Skipping card: {}", e.getMessage());
                    }
                }

                sleep(PAGE_DELAY);
            }

        } catch (Exception e) {
            log.error("[Bazar-Prod] Scrape failed for {}: {}", categoryUrl, e.getMessage(), e);
        }

        log.info("[Bazar-Prod] Done — {} products for {}", result.size(), categoryUrl);
        return result;
    }

    // ── Card parsing ──────────────────────────────────────────────────────────

    private Product parseCard(ElementHandle card, Category category, Market market) {

        // ── Name ──────────────────────────────────────────────────────────────
        // span.visually-hidden inside the anchor holds the clean product name.
        // This is the Shopify accessibility pattern — it's the most reliable name source.
        String name = safeText(card, "a.full-unstyled-link span.visually-hidden");
        if (isBlank(name)) {
            // Fallback: card-information__text h5 also holds the name
            name = safeText(card, "span.card-information__text");
        }
        if (isBlank(name)) {
            log.debug("[Bazar-Prod] Card has no name — skipped");
            return null;
        }

        // ── Link ──────────────────────────────────────────────────────────────
        // Use the canonical product link from a.full-unstyled-link
        String href = safeAttr(card, "a.full-unstyled-link", "href");
        if (isBlank(href)) {
            log.debug("[Bazar-Prod] Card '{}' has no link — skipped", name);
            return null;
        }
        // Remove any position tracking params from the URL — keep only the clean path
        String link = BASE_URL + href.split("\\?")[0];

        // ── Prices ────────────────────────────────────────────────────────────
        //
        // datalayer-price on <form> = current selling price (clean numeric string).
        //
        // s.price-item--regular.adjusted_compare = original/compare price.
        // This element only exists when the product is on sale.
        //
        // Decision:
        //   Has adjusted_compare? → discounted product
        //     originalPrice  = adjusted_compare value
        //     discountPrice  = datalayer-price value
        //   No adjusted_compare? → normal product
        //     originalPrice  = datalayer-price value
        //     discountPrice  = null
        //
        String datalayerPrice = safeAttr(card, "form[datalayer-price]", "datalayer-price");
        String comparePrice   = safeText(card, "s.price-item--regular.adjusted_compare");

        BigDecimal currentPrice = parseMoney(datalayerPrice);
        if (currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            // datalayer-price missing — try reading from visible price span
            String fallbackPrice = safeText(card, "span.price-item--regular");
            currentPrice = parseMoney(fallbackPrice);
        }
        if (currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("[Bazar-Prod] No price for '{}' — skipped", name);
            return null;
        }

        BigDecimal originalPrice;
        BigDecimal discountPrice = null;
        BigDecimal discountPct   = null;

        if (!isBlank(comparePrice)) {
            BigDecimal original = parseMoney(comparePrice);
            if (original.compareTo(BigDecimal.ZERO) > 0
                    && original.compareTo(currentPrice) > 0) {
                originalPrice = original;
                discountPrice = currentPrice;
                discountPct   = original.subtract(currentPrice)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(original, 0, RoundingMode.HALF_UP);
            } else {
                originalPrice = currentPrice;
            }
        } else {
            originalPrice = currentPrice;
        }

        // ── Image ─────────────────────────────────────────────────────────────
        // Shopify CDN images use protocol-relative URLs: //bazarstore.az/cdn/shop/...
        // We take the src attribute of the product image (the last img in the media div,
        // which is the full product image, not a placeholder or badge).
        // Convert protocol-relative to https.
        String imageUrl = extractImageUrl(card);

        return persistence.upsertProduct(
                name, link, originalPrice, discountPrice, discountPct,
                imageUrl, category, market);
    }

    // ── Image extraction ──────────────────────────────────────────────────────

    /**
     * Extracts the product image URL from the card.
     *
     * Shopify card structure:
     *   div.card__media-full-spacer > div.media > img[srcset][src]
     *
     * The src attribute uses protocol-relative format: //bazarstore.az/cdn/...
     * We normalise it to https:.
     *
     * We intentionally avoid the srcset's largest size (3840px) — the src
     * already points to a reasonable 533px variant.
     */
    private String extractImageUrl(ElementHandle card) {
        try {
            ElementHandle img = card.querySelector("div.card__media-full-spacer img.motion-reduce");
            if (img == null) img = card.querySelector("div.media img");
            if (img == null) return "";

            String src = img.getAttribute("src");
            if (src == null || src.isBlank()) return "";

            // Normalise protocol-relative URL
            if (src.startsWith("//")) return "https:" + src;
            if (src.startsWith("http")) return src;
            return BASE_URL + src;

        } catch (Exception e) {
            return "";
        }
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    /**
     * Reads the total number of pages from the hidden Shopify pagination nav.
     *
     * Shopify renders a complete pagination nav even when using infinite scroll.
     * It is hidden via CSS (display:none on .pagination-wrapper) but the links
     * are in the DOM and queryable via JavaScript.
     *
     * The nav looks like:
     *   ul.pagination__list
     *     li > span.pagination__item--current  ← current page (no link)
     *     li > a.pagination__item[href*="page=2"]
     *     li > a.pagination__item[href*="page=3"]
     *     li > a.pagination__item-arrow        ← "next" arrow (skip this)
     *
     * Returns 1 (single page) if pagination is absent — safe fallback.
     */
    private int getTotalPages(Page page) {
        try {
            Object result = page.evaluate("""
                () => {
                    const links = document.querySelectorAll(
                        'ul.pagination__list a.pagination__item[href*="page="]');
                    let max = 1;
                    for (const a of links) {
                        // Skip the "next" arrow link
                        if (a.classList.contains('pagination__item-arrow')) continue;
                        const m = a.href.match(/[?&]page=(\\d+)/);
                        if (m) max = Math.max(max, parseInt(m[1], 10));
                    }
                    // Also consider the current page span
                    const current = document.querySelector(
                        'ul.pagination__list span.pagination__item--current');
                    if (current) {
                        const n = parseInt(current.textContent.trim(), 10);
                        if (!isNaN(n)) max = Math.max(max, n);
                    }
                    return max;
                }
            """);
            if (result instanceof Number) return ((Number) result).intValue();
            return 1;
        } catch (Exception e) {
            log.debug("[Bazar-Prod] Could not read pagination: {}", e.getMessage());
            return 1;
        }
    }

    // ── Page helpers ──────────────────────────────────────────────────────────

    /**
     * Waits for the Shopify product grid to be present and have at least one item.
     * Returns false (never throws) when the category is empty — valid stop condition.
     *
     * We wait for ul#product-grid which is Shopify's standard grid ID.
     * li.grid__item is the card selector — also stable across Shopify themes.
     */
    private boolean waitForGrid(Page page) {
        try {
            page.waitForSelector("ul#product-grid li.grid__item",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(12_000));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Dismisses both known Bazarstore popup types.
     * Called only on the first page load — popups do not reappear on pagination.
     */
    private void dismissPopups(Page page) {
        dismissPushOwl(page);
        dismissPopConvert(page);
    }

    private void dismissPushOwl(Page page) {
        try {
            page.waitForSelector("dialog.pushowl-optin",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(4_000));
            try {
                page.click("button.js-pushowl-no-button",
                        new Page.ClickOptions().setTimeout(3_000));
                log.info("[Bazar-Prod] Dismissed PushOwl dialog");
            } catch (Exception e) {
                page.evaluate("document.querySelector('dialog.pushowl-optin')?.remove()");
            }
        } catch (Exception e) {
            log.debug("[Bazar-Prod] No PushOwl dialog");
        }
    }

    private void dismissPopConvert(Page page) {
        try {
            page.waitForSelector("#pc-modal-backdrop",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(4_000));
            try {
                page.click("#pc-main-close-button",
                        new Page.ClickOptions().setTimeout(3_000));
                log.info("[Bazar-Prod] Dismissed Pop-Convert modal");
            } catch (Exception e) {
                page.evaluate("document.getElementById('pc-modal-backdrop')?.remove()");
            }
        } catch (Exception e) {
            log.debug("[Bazar-Prod] No Pop-Convert modal");
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
                log.warn("[Bazar-Prod] Navigate attempt {}/{}: {}", i, maxAttempts, e.getMessage());
                if (i < maxAttempts) sleep((long) Math.pow(2, i) * 1_000);
            }
        }
        return false;
    }

    /**
     * Strips any existing page= parameter so we can append a fresh one.
     * Bazarstore filter URLs always have a query string, so we use & not ?.
     *
     * Example:
     *   /collections/meyva-terevaz?filter.v.availability=1&page=3
     *   → /collections/meyva-terevaz?filter.v.availability=1
     */
    private String stripPageParam(String url) {
        return url.replaceAll("[&?]page=\\d+", "").replaceAll("\\?$", "");
    }

    /**
     * Appends &page=N (Bazarstore URLs always have existing query params from filters).
     * If somehow the URL has no query string, fall back to ?page=N.
     */
    private String appendPageParam(String base, int pageNum) {
        if (pageNum == 1) return base;
        return base.contains("?") ? base + "&page=" + pageNum
                : base + "?page=" + pageNum;
    }

    // ── Value helpers ─────────────────────────────────────────────────────────

    private String safeText(ElementHandle root, String css) {
        try {
            ElementHandle el = root.querySelector(css);
            return el == null ? "" : el.innerText().trim();
        } catch (Exception e) { return ""; }
    }

    private String safeAttr(ElementHandle root, String css, String attr) {
        try {
            ElementHandle el = root.querySelector(css);
            if (el == null) return "";
            String v = el.getAttribute(attr);
            return v != null ? v.trim() : "";
        } catch (Exception e) { return ""; }
    }

    private BigDecimal parseMoney(String raw) {
        if (raw == null || raw.isBlank()) return BigDecimal.ZERO;
        try {
            String clean = raw.replaceAll("[^0-9.]", "");
            int dot = clean.indexOf('.');
            if (dot >= 0) {
                clean = clean.substring(0, dot + 1)
                        + clean.substring(dot + 1).replace(".", "");
            }
            return clean.isEmpty() ? BigDecimal.ZERO : new BigDecimal(clean);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
