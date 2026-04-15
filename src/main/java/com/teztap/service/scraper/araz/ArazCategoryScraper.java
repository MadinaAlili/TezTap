package com.teztap.service.scraper.araz;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import com.teztap.model.Category;
import com.teztap.model.Market;
import com.teztap.repository.MarketRepository;
import com.teztap.service.scraper.PlaywrightManager;
import com.teztap.service.scraper.Scraper;
import com.teztap.service.scraper.ScraperPersistenceService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ArazCategoryScraper implements Scraper<List<Category>> {

    private static final Logger log = LoggerFactory.getLogger(ArazCategoryScraper.class);

    private static final String BASE_URL    = "https://www.arazmarket.az";
    private static final String MARKET_NAME = "ARAZ";
    private static final int    NAV_TIMEOUT = 30_000;

    private final PlaywrightManager      playwrightManager;
    private final ScraperPersistenceService persistence;
    private final MarketRepository       marketRepository;

    // ---------------------------------------------------------------------------
    // JavaScript that extracts top-level categories from the open dropdown.
    //
    // WHY JS instead of CSS selectors:
    //   Araz is a Next.js app. Its CSS module classes contain auto-generated
    //   hashes (e.g. "products-dropdown_list_item__Y3QzY") that change on every
    //   frontend deployment.  Relying on those class names in Java code means
    //   the scraper silently returns 0 categories after every deploy.
    //
    // This JS uses only:
    //   - href attribute patterns          → structural, never changes
    //   - span child element presence      → semantic distinction between
    //                                        top-level and sub-categories
    //
    // Top-level category anchors:  <a href="/az/categories/..."><span>Name</span>...</a>
    // Sub-category anchors:        <a href="/az/categories/...">Name<svg/></a>  (no span)
    // ---------------------------------------------------------------------------
    private static final String EXTRACT_CATEGORIES_JS = """
        () => {
            const results = [];
            const seen    = new Set();
            const links   = document.querySelectorAll('a[href^="/az/categories/"]');

            for (const link of links) {
                const href = link.getAttribute('href');
                if (!href || seen.has(href)) continue;

                // Only top-level categories have a <span> wrapping the name text.
                // Sub-categories put the text directly inside the anchor.
                const span = link.querySelector('span');
                if (!span) continue;

                const name = span.innerText.trim();
                if (!name) continue;

                seen.add(href);
                results.push({ href, name });
            }
            return results;
        }
    """;

    @Override
    public List<Category> scrape(String url) {
        Market market = marketRepository.findByName(MARKET_NAME).get();
        List<Category> result = new ArrayList<>();

        try (BrowserContext ctx = playwrightManager.newContext()) {
            Page page = ctx.newPage();

            // Block all media — this page only needs HTML + JS to render
            ctx.route("**/*.{png,jpg,jpeg,gif,webp,svg,ico,woff,woff2,ttf,otf}",
                    route -> route.abort());

            if (!navigateWithRetry(page, url, 3)) {
                log.error("[Araz-Cat] Cannot load homepage — aborting");
                return result;
            }

            // Wait for Next.js hydration to complete before interacting
            waitForHydration(page);

            // Dismiss popup if it appeared
            dismissPopup(page);

            // Click the "Məhsullar" button — the dropdown DOM does not exist
            // in the initial HTML, it is injected by React only after this click.
            if (!clickProductsButton(page)) {
                log.error("[Araz-Cat] Could not open category dropdown");
                return result;
            }

            // Wait for at least one category link to be rendered
            try {
                page.waitForSelector("a[href^='/az/categories/']",
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(10_000));
            } catch (Exception e) {
                log.error("[Araz-Cat] Category links never appeared: {}", e.getMessage());
                return result;
            }

            // Extract all top-level category links via JS
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> categories =
                    (List<Map<String, Object>>) page.evaluate(EXTRACT_CATEGORIES_JS);

            if (categories == null || categories.isEmpty()) {
                log.warn("[Araz-Cat] JS extraction returned 0 categories");
                return result;
            }

            log.info("[Araz-Cat] Found {} categories", categories.size());

            for (Map<String, Object> cat : categories) {
                try {
                    String name = (String) cat.get("name");
                    String href = (String) cat.get("href");

                    if (isBlank(name) || isBlank(href)) continue;

                    String fullUrl = BASE_URL + href;
                    Category saved = persistence.upsertCategory(name, fullUrl, market);
                    result.add(saved);
                } catch (Exception e) {
                    log.warn("[Araz-Cat] Skipping entry: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("[Araz-Cat] Scrape failed: {}", e.getMessage(), e);
        }

        log.info("[Araz-Cat] Done — {} categories", result.size());
        return result;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Waits for Next.js to finish client-side hydration.
     * We wait for any <h1> or <main> to be visible — these are injected by React
     * after the initial HTML shell is hydrated. Without this, page.url() may
     * return the pre-redirect shell URL.
     */
    private void waitForHydration(Page page) {
        try {
            page.waitForSelector("h1, main",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(8_000));
        } catch (Exception ignored) {
            // Not all pages have h1/main — that's fine, proceed anyway
        }
    }

    /**
     * Dismisses the full-screen promotional popup using three fallback strategies.
     * The popup has stable data-testid attributes — those are used preferentially.
     * Never throws; a missing popup is completely normal.
     */
    private void dismissPopup(Page page) {
        try {
            page.waitForSelector("[data-testid='modal']",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(5_000));

            // Strategy 1: react-responsive-modal close button (most stable — data-testid)
            try {
                page.click("[data-testid='close-button']",
                        new Page.ClickOptions().setTimeout(3_000));
                log.info("[Araz-Cat] Popup dismissed via close button");
                return;
            } catch (Exception ignored) {}

            // Strategy 2: inner popup SVG close icon (may change with redesigns)
            try {
                page.click(".popup_close__e6MO_ svg",
                        new Page.ClickOptions().setTimeout(3_000));
                log.info("[Araz-Cat] Popup dismissed via SVG icon");
                return;
            } catch (Exception ignored) {}

            // Strategy 3: nuclear option — remove from DOM
            page.evaluate("document.querySelector('[data-testid=\"root\"]')?.remove()");
            log.info("[Araz-Cat] Popup removed via JS");

        } catch (Exception e) {
            log.debug("[Araz-Cat] No popup present ({})", e.getMessage());
        }
    }

    private boolean clickProductsButton(Page page) {
        try {
            page.waitForSelector(
                    "div.header_products_btn__yC88K button, button:has(span:text('Məhsullar'))",
                    new Page.WaitForSelectorOptions().setTimeout(8_000));

            // Try specific class first, fall back to text-based selector
            try {
                page.click("div.header_products_btn__yC88K button");
            } catch (Exception e) {
                page.click("button:has(span:text('Məhsullar'))");
            }
            log.info("[Araz-Cat] Clicked products button");
            return true;
        } catch (Exception e) {
            log.error("[Araz-Cat] Products button not found: {}", e.getMessage());
            return false;
        }
    }

    private boolean navigateWithRetry(Page page, String url, int maxAttempts) {
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(NAV_TIMEOUT));
                return true;
            } catch (Exception e) {
                log.warn("[Araz-Cat] Navigate attempt {}/{}: {}", i, maxAttempts, e.getMessage());
                if (i < maxAttempts) sleep((long) Math.pow(2, i) * 1_000);
            }
        }
        return false;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}