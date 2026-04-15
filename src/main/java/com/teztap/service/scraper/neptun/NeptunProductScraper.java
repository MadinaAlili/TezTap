package com.teztap.service.scraper.neptun;

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
public class NeptunProductScraper implements Scraper<List<Product>> {

    private static final Logger log = LoggerFactory.getLogger(NeptunProductScraper.class);

    private static final String BASE_URL    = "https://neptun.az";
    private static final String MARKET_NAME = "NEPTUN";
    private static final int    NAV_TIMEOUT = 30_000;
    private static final int    MAX_PAGES   = 50;
    private static final long   PAGE_DELAY  = 800;

    private final PlaywrightManager         playwrightManager;
    private final ScraperPersistenceService persistence;
    private final CategoryRepository        categoryRepository;
    private final MarketRepository          marketRepository;

    @Override
    public List<Product> scrape(String categoryUrl) {
        Market   market   = marketRepository.findByName(MARKET_NAME).get();
        Category category = categoryRepository.findByUrl(categoryUrl).orElse(null);
        List<Product> result = new ArrayList<>();

        try (BrowserContext ctx = playwrightManager.newContext()) {
            Page page = ctx.newPage();

            // Block images at network level — OpenCart product data is server-rendered HTML
            ctx.route("**/*.{png,jpg,jpeg,gif,webp,ico,woff,woff2,ttf,otf}",
                    route -> route.abort());

            if (!navigateWithRetry(page, categoryUrl, 3)) {
                log.error("[Neptun-Prod] Cannot load {} — skipping", categoryUrl);
                return result;
            }

            // OpenCart uses ?page=N pagination. Read the URL after navigation
            // to capture any redirect (some Neptun category URLs redirect to canonical forms).
            // We wait for the product list to be present first, so the page is stable.
            try {
                page.waitForSelector("div.products-list",
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.ATTACHED)
                                .setTimeout(10_000));
            } catch (Exception e) {
                log.info("[Neptun-Prod] No products found for {} — may be empty category",
                        categoryUrl);
                return result;
            }

            String resolvedBase = stripPageParam(page.url());
            log.info("[Neptun-Prod] Base URL: {}", resolvedBase);

            for (int pageNum = 1; pageNum <= MAX_PAGES; pageNum++) {

                if (pageNum > 1) {
                    String pagedUrl = appendPageParam(resolvedBase, pageNum);
                    log.info("[Neptun-Prod] Page {}: {}", pageNum, pagedUrl);
                    if (!navigateWithRetry(page, pagedUrl, 3)) {
                        log.warn("[Neptun-Prod] Cannot load page {} — stopping", pageNum);
                        break;
                    }
                }

                // SCROLL FIRST, then query cards.
                // Reason: OpenCart product pages can have lazy-loaded images and
                // occasionally deferred product rendering on long category pages.
                // Querying BEFORE scroll risks stale ElementHandle references if
                // the browser re-renders the grid after lazy-loading triggers.
                scrollToBottom(page);

                // Query AFTER scroll so references are fresh
                List<ElementHandle> cards = waitForCards(page);
                if (cards.isEmpty()) {
                    log.info("[Neptun-Prod] No cards on page {} — done", pageNum);
                    break;
                }

                log.info("[Neptun-Prod] {} cards on page {}", cards.size(), pageNum);

                for (ElementHandle card : cards) {
                    try {
                        Product p = parseAndPersist(card, category, market);
                        if (p != null) result.add(p);
                    } catch (Exception e) {
                        log.warn("[Neptun-Prod] Skipping card: {}", e.getMessage());
                    }
                }

                // Stop if no next-page link exists
                if (!hasNextPage(page)) {
                    log.info("[Neptun-Prod] No next page after page {}", pageNum);
                    break;
                }

                sleep(PAGE_DELAY);
            }

        } catch (Exception e) {
            log.error("[Neptun-Prod] Scrape failed for {}: {}", categoryUrl, e.getMessage(), e);
        }

        log.info("[Neptun-Prod] Done — {} products for {}", result.size(), categoryUrl);
        return result;
    }

    // ── Card parsing ──────────────────────────────────────────────────────────

    private Product parseAndPersist(ElementHandle card, Category category, Market market) {

        // WHY these selectors are stable:
        //   Neptun uses OpenCart — a PHP CMS. The CSS classes (products-list,
        //   product-layout, caption, price-new, price-old) are part of the OpenCart
        //   default theme template and are NOT auto-generated. They only change
        //   if someone manually edits the theme files.

        // ── Name & link ───────────────────────────────────────────────────────
        String name = safeText(card, "div.caption h4 a");
        String href = safeAttr(card, "div.caption h4 a", "href");

        if (isBlank(name) || isBlank(href)) {
            log.debug("[Neptun-Prod] Card missing name or link — skipped");
            return null;
        }

        String link = href.startsWith("http") ? href : BASE_URL + href;

        // ── Prices ────────────────────────────────────────────────────────────
        //
        // Normal:    <span class="price-new">4.35₼</span>
        // On sale:   <span class="price-new">2.99₼</span>
        //            <span class="price-old">5.00₼</span>  ← pre-discount price
        //
        // When price-old exists:
        //   originalPrice  = price-old (what it used to cost)
        //   discountPrice  = price-new (what customer pays now)
        //
        String priceNewText = safeText(card, "div.price span.price-new");
        String priceOldText = safeText(card, "div.price span.price-old");

        BigDecimal priceNew = parseMoney(priceNewText);
        if (priceNew.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("[Neptun-Prod] No price for '{}' — skipped", name);
            return null;
        }

        BigDecimal originalPrice;
        BigDecimal discountPrice = null;
        BigDecimal discountPct   = null;

        if (!priceOldText.isBlank()) {
            BigDecimal priceOld = parseMoney(priceOldText);
            if (priceOld.compareTo(BigDecimal.ZERO) > 0) {
                originalPrice = priceOld;
                discountPrice = priceNew;
                discountPct   = priceOld.subtract(priceNew)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(priceOld, 0, RoundingMode.HALF_UP);
            } else {
                originalPrice = priceNew;
            }
        } else {
            originalPrice = priceNew;
        }

        // ── Image ─────────────────────────────────────────────────────────────
        // img.img-1 is the primary product thumbnail in the OpenCart theme.
        // Try src first, fall back to data-src for lazy-loaded images.
        String imageUrl = safeAttr(card, "div.product-image-container img.img-1", "src");
        if (isBlank(imageUrl)) {
            imageUrl = safeAttr(card, "div.product-image-container img.img-1", "data-src");
        }

        return persistence.upsertProduct(
                name, link, originalPrice, discountPrice, discountPct,
                imageUrl, category, market);
    }

    // ── Page helpers ──────────────────────────────────────────────────────────

    /**
     * Synchronous incremental scroll.
     * See ArazProductScraper for explanation of why async JS is not used.
     */
    private void scrollToBottom(Page page) {
        try {
            long lastHeight = -1;
            for (int i = 0; i < 20; i++) {
                page.evaluate("window.scrollTo(0, document.body.scrollHeight)");
                sleep(350);
                long currentHeight = ((Number) page.evaluate(
                        "document.body.scrollHeight")).longValue();
                if (currentHeight == lastHeight) break;
                lastHeight = currentHeight;
            }
            page.evaluate("window.scrollTo(0, 0)");
            sleep(200);
        } catch (Exception e) {
            log.debug("[Neptun-Prod] Scroll incomplete: {}", e.getMessage());
        }
    }

    /**
     * Waits for product cards then returns them.
     * Returns empty list (never throws) — caller uses empty list as stop signal.
     */
    private List<ElementHandle> waitForCards(Page page) {
        try {
            page.waitForSelector("div.products-list div.product-layout",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(8_000));
            return page.querySelectorAll("div.products-list div.product-layout");
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Returns true if a pagination "next" link or numbered page beyond the
     * current one exists on the page.
     *
     * OpenCart renders pagination as:
     *   <ul class="pagination">
     *     <li><a href="...?page=1">1</a></li>
     *     <li><a href="...?page=2">2</a></li>
     *     <li class="active"><span>3</span></li>  ← current page (no link)
     *     <li><a href="...?page=4">4</a></li>
     *     <li><a href="...">›</a></li>            ← "next" arrow
     *   </ul>
     *
     * We look for the next-arrow OR any page link in the pagination area.
     * Using JS for robustness against minor pagination template changes.
     */
    private boolean hasNextPage(Page page) {
        try {
            Object result = page.evaluate("""
                () => {
                    // OpenCart pagination: li elements inside ul.pagination
                    const pagination = document.querySelector('ul.pagination');
                    if (!pagination) return false;

                    // If there's an anchor after the active item, more pages exist
                    const active = pagination.querySelector('li.active');
                    if (active && active.nextElementSibling) {
                        const sibling = active.nextElementSibling;
                        // Check it's a real page link, not disabled
                        if (sibling.querySelector('a')) return true;
                    }

                    // Fallback: check for any link containing page= with a number
                    const links = pagination.querySelectorAll('a[href*="page="]');
                    return links.length > 0;
                }
            """);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
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
                log.warn("[Neptun-Prod] Navigate attempt {}/{}: {}",
                        i, maxAttempts, e.getMessage());
                if (i < maxAttempts) sleep((long) Math.pow(2, i) * 1_000);
            }
        }
        return false;
    }

    private String stripPageParam(String url) {
        return url.replaceAll("[&?]page=\\d+", "").replaceAll("\\?$", "");
    }

    private String appendPageParam(String base, int pageNum) {
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

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}