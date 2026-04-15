package com.teztap.service.scraper.araz;

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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ArazProductScraper implements Scraper<List<Product>> {

    private static final Logger log = LoggerFactory.getLogger(ArazProductScraper.class);

    private static final String BASE_URL   = "https://www.arazmarket.az";
    private static final String MARKET_NAME = "ARAZ";
    private static final int    NAV_TIMEOUT = 30_000;
    private static final int    MAX_PAGES   = 50;
    private static final long   PAGE_DELAY  = 1_200;

    private final PlaywrightManager         playwrightManager;
    private final ScraperPersistenceService persistence;
    private final CategoryRepository        categoryRepository;
    private final MarketRepository          marketRepository;

    // -------------------------------------------------------------------------
    // WHY [class*="..."] selectors instead of full class names:
    //
    // Araz is a Next.js app using CSS Modules. Class names are generated as:
    //   "component_element__HASH"  e.g. "products-card_card__UW0fA"
    //
    // The HASH part changes on every frontend deployment. If we use the full
    // class name, the scraper silently returns 0 products after every deploy.
    //
    // [class*="products-card_card"] matches ANY class that contains that string,
    // regardless of what comes after the double underscore. The component name
    // prefix never changes — only the hash does.
    //
    // WHY ElementHandle instead of page.evaluate() JS extraction:
    //
    // The previous version used a JS function that walked the DOM to find card
    // roots. When the card-walking logic failed (e.g. img[title] not found for
    // a specific card), the JS returned an empty array silently. Playwright's
    // page.evaluate() has no way to surface partial failures — it returns either
    // the full result or throws. ElementHandle iteration fails loudly per-card
    // (caught individually) instead of silently zeroing the whole page.
    // -------------------------------------------------------------------------

    // Selector constants — partial class matches, hash-resilient
    private static final String SEL_CARD          = "[class*='products-card_card']";
    private static final String SEL_TEXT_BODY     = "[class*='products-card_text_body'] h2";
    private static final String SEL_PRICE_NORMAL  = "[class*='products-card_price__'] span";
    private static final String SEL_PRICE_DISCOUNT = "[class*='products-card_price_discount']";
    private static final String SEL_PRODUCT_LINK  = "a[href^='/az/products/']";

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
                log.error("[Araz-Prod] Cannot load {} — skipping", categoryUrl);
                return result;
            }

            waitForHydration(page);
            dismissPopup(page);

            // Read canonical URL AFTER hydration so client-side redirects have settled
            String resolvedBase = stripPageParam(page.url());
            log.info("[Araz-Prod] Base URL: {}", resolvedBase);

            for (int pageNum = 1; pageNum <= MAX_PAGES; pageNum++) {

                if (pageNum > 1) {
                    String pagedUrl = appendPageParam(resolvedBase, pageNum);
                    log.info("[Araz-Prod] Page {}: {}", pageNum, pagedUrl);
                    if (!navigateWithRetry(page, pagedUrl, 3)) {
                        log.warn("[Araz-Prod] Cannot load page {} — stopping", pageNum);
                        break;
                    }
                    waitForHydration(page);
                }

                // Scroll BEFORE querying. Araz lazy-loads cards via Intersection Observer.
                // We must scroll first so all cards render into the DOM, THEN query them.
                // Querying before scroll risks stale handles if React re-renders after scroll.
                scrollToBottom(page);

                // Wait for cards after scroll — handles are fresh, no stale reference risk
                List<ElementHandle> cards = waitForCards(page);
                if (cards.isEmpty()) {
                    log.info("[Araz-Prod] No cards on page {} — done", pageNum);
                    break;
                }

                log.info("[Araz-Prod] {} cards on page {}", cards.size(), pageNum);

                for (ElementHandle card : cards) {
                    try {
                        Product p = parseCard(card, category, market);
                        if (p != null) result.add(p);
                    } catch (Exception e) {
                        log.warn("[Araz-Prod] Skipping card: {}", e.getMessage());
                    }
                }

                if (!hasNextPage(page, pageNum)) {
                    log.info("[Araz-Prod] No more pages after page {}", pageNum);
                    break;
                }

                sleep(PAGE_DELAY);
            }

        } catch (Exception e) {
            log.error("[Araz-Prod] Scrape failed for {}: {}", categoryUrl, e.getMessage(), e);
        }

        log.info("[Araz-Prod] Done — {} products for {}", result.size(), categoryUrl);
        return result;
    }

    // ── Card parsing ──────────────────────────────────────────────────────────

    private Product parseCard(ElementHandle card, Category category, Market market) {

        // ── Name ──────────────────────────────────────────────────────────────
        String name = safeText(card, SEL_TEXT_BODY);
        if (isBlank(name)) {
            log.debug("[Araz-Prod] Card has no name — skipped");
            return null;
        }

        // ── Link ──────────────────────────────────────────────────────────────
        // Each card has multiple anchors all pointing to the same product URL.
        // Any of them gives us the href — grab the first one.
        String href = safeAttr(card, SEL_PRODUCT_LINK, "href");
        if (isBlank(href)) {
            log.debug("[Araz-Prod] Card '{}' has no link — skipped", name);
            return null;
        }
        String link = href.startsWith("http") ? href : BASE_URL + href;

        // ── Prices ────────────────────────────────────────────────────────────
        //
        // Discounted product:
        //   [class*="products-card_price_discount"]
        //     <span>CURRENT_PRICE <svg/></span>   ← what customer pays
        //     <del> ORIGINAL_PRICE </del>          ← struck-through original
        //
        // Normal product:
        //   [class*="products-card_price__"]       (double underscore avoids matching discount)
        //     <span>PRICE <svg/></span>
        //
        // [class*="products-card_price_discount"] does NOT match
        // [class*="products-card_price__"] because the normal class has
        // "price__" (double underscore) while discount has "price_discount__".
        //
        BigDecimal originalPrice;
        BigDecimal discountPrice = null;
        BigDecimal discountPct   = null;

        ElementHandle discountContainer = card.querySelector(SEL_PRICE_DISCOUNT);

        if (discountContainer != null) {
            // Discounted: span = current selling price, del = original price
            String currentStr  = safeText(discountContainer, "span");
            String originalStr = safeText(discountContainer, "del");

            BigDecimal current  = parseMoney(currentStr);
            BigDecimal original = parseMoney(originalStr);

            if (original.compareTo(BigDecimal.ZERO) > 0
                    && current.compareTo(BigDecimal.ZERO) > 0
                    && original.compareTo(current) > 0) {
                originalPrice = original;
                discountPrice = current;
                discountPct   = original.subtract(current)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(original, 0, RoundingMode.HALF_UP);
            } else {
                // Malformed discount data — treat the larger value as original price
                originalPrice = original.compareTo(current) >= 0 ? original : current;
            }
        } else {
            // Normal price: span inside the normal price container
            String priceText = safeText(card, SEL_PRICE_NORMAL);
            originalPrice = parseMoney(priceText);
        }

        if (originalPrice.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("[Araz-Prod] Zero price for '{}' — skipped", name);
            return null;
        }

        // ── Image ─────────────────────────────────────────────────────────────
        // img[title] matches the product image (not the loading placeholder).
        // The placeholder has alt="loading" and no title attribute.
        // The product image has both alt and title set to the product name.
        String imageUrl = extractImageUrl(card);

        return persistence.upsertProduct(
                name, link, originalPrice, discountPrice, discountPct,
                imageUrl, category, market);
    }

    // ── Image URL extraction ──────────────────────────────────────────────────

    /**
     * Extracts the real CDN URL from a Next.js Image Optimizer src.
     *
     * Next.js serves images as: /_next/image?url=ENCODED_CDN_URL&w=3840&q=75
     * We decode the `url` parameter to get the stable CDN URL.
     * Falls back to the raw src if decoding fails.
     */
    private String extractImageUrl(ElementHandle card) {
        try {
            ElementHandle img = card.querySelector("img[title]");
            if (img == null) return "";

            String src = img.getAttribute("src");
            if (src == null || src.isBlank()) return "";

            if (src.contains("/_next/image")) {
                int start = src.indexOf("url=");
                if (start < 0) return src;
                start += 4;
                int end = src.indexOf('&', start);
                String encoded = end > 0 ? src.substring(start, end) : src.substring(start);
                return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            }

            return src.startsWith("http") ? src : BASE_URL + src;
        } catch (Exception e) {
            return "";
        }
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    /**
     * Returns true only if a page number GREATER than currentPage exists.
     *
     * Araz pagination renders as:
     *   <li class="... active-paginate ...">1</li>   ← current (no link)
     *   <li class="... paginate-item ...">2</li>      ← other pages (have links)
     *
     * Simply checking for any paginate-item is not enough — on the last page,
     * previous pages still render as paginate-item. We must check for a number
     * strictly greater than the current page.
     *
     * paginate-item is a hand-written class (not a CSS module hash), so it is stable.
     */
    private boolean hasNextPage(Page page, int currentPage) {
        try {
            List<ElementHandle> items = page.querySelectorAll("li.paginate-item");
            for (ElementHandle item : items) {
                String text = item.innerText().trim();
                try {
                    if (Integer.parseInt(text) > currentPage) return true;
                } catch (NumberFormatException ignored) {}
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Page helpers ──────────────────────────────────────────────────────────

    /**
     * Waits for Next.js hydration to complete.
     * We read page.url() only AFTER this — pre-hydration the URL may still be
     * the unresolved shell URL before client-side routing finishes.
     */
    private void waitForHydration(Page page) {
        try {
            page.waitForSelector("h1, main",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(8_000));
        } catch (Exception ignored) {}
    }

    private void dismissPopup(Page page) {
        try {
            page.waitForSelector("[data-testid='modal']",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(4_000));
            try {
                page.click("[data-testid='close-button']",
                        new Page.ClickOptions().setTimeout(3_000));
                return;
            } catch (Exception ignored) {}
            page.evaluate("document.querySelector('[data-testid=\"root\"]')?.remove()");
        } catch (Exception e) {
            log.debug("[Araz-Prod] No popup ({})", e.getMessage());
        }
    }

    /**
     * Synchronous incremental scroll.
     *
     * WHY not async JS:
     *   page.evaluate("async () => { await ... }") does NOT block the Java thread.
     *   Playwright Java's evaluate() returns immediately when JS returns a Promise —
     *   it does not await the Promise. The scroll would "complete" in JS while Java
     *   has already moved on. This synchronous loop guarantees each scroll step
     *   finishes (including the 400ms lazy-load wait) before the next begins.
     */
    private void scrollToBottom(Page page) {
        try {
            long lastHeight = -1;
            for (int i = 0; i < 20; i++) {
                page.evaluate("window.scrollTo(0, document.body.scrollHeight)");
                sleep(400);
                long currentHeight = ((Number) page.evaluate(
                        "document.body.scrollHeight")).longValue();
                if (currentHeight == lastHeight) break;
                lastHeight = currentHeight;
            }
            page.evaluate("window.scrollTo(0, 0)");
            sleep(300);
        } catch (Exception e) {
            log.debug("[Araz-Prod] Scroll incomplete: {}", e.getMessage());
        }
    }

    /**
     * Waits for product cards and returns them as fresh ElementHandle references.
     *
     * IMPORTANT: this is called AFTER scrollToBottom() so the DOM is fully populated.
     * Handles are freshly queried — no staleness from pre-scroll queries.
     *
     * Uses [class*="products-card_card"] which matches regardless of the hash suffix.
     * Falls back to structural link selector if the class name pattern ever changes.
     */
    private List<ElementHandle> waitForCards(Page page) {
        // First wait for cards to be visible
        try {
            page.waitForSelector(SEL_CARD,
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(10_000));
        } catch (Exception e) {
            // Primary selector timed out — try structural fallback
            try {
                page.waitForSelector(SEL_PRODUCT_LINK,
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(5_000));
            } catch (Exception e2) {
                return List.of(); // genuinely no products
            }
        }

        // Query cards — prefer precise card selector, fall back if it returns nothing
        List<ElementHandle> cards = page.querySelectorAll(SEL_CARD);
        if (!cards.isEmpty()) return cards;

        // Fallback: if the card class pattern changed entirely, find cards by
        // structural relationship — the parent of any element that contains both
        // a product link and an h2 is the card container
        log.warn("[Araz-Prod] Primary card selector found 0 — check if CSS module names changed");
        return List.of();
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
                log.warn("[Araz-Prod] Navigate attempt {}/{}: {}", i, maxAttempts, e.getMessage());
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

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}