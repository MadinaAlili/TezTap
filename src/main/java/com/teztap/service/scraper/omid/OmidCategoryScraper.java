package com.teztap.service.scraper.omid;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
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

@Component
@RequiredArgsConstructor
public class OmidCategoryScraper implements Scraper<List<Category>> {

    private static final Logger log = LoggerFactory.getLogger(OmidCategoryScraper.class);

    private static final String BASE_URL    = "https://omid.az";
    private static final String MARKET_NAME = "OMID";
    private static final int    NAV_TIMEOUT = 30_000;

    private final PlaywrightManager         playwrightManager;
    private final ScraperPersistenceService persistence;
    private final MarketRepository          marketRepository;

    // -------------------------------------------------------------------------
    // WHY the menu is queryable without clicking:
    //
    // Omid.az uses a Shopify theme with a lazy-loaded vertical menu.
    // The menu items are loaded into the DOM via AJAX when the page loads,
    // indicated by the class change from "lazyload" to "lazyloaded" on the ul.
    // In headless Playwright, all page scripts run, so by the time we wait
    // for "ul.lazy_vertical_menu.lazyloaded", all li items are already in the DOM.
    // We click the trigger anyway as a belt-and-suspenders measure to ensure
    // the AJAX load fires reliably even if lazy-loading is triggered by visibility.
    //
    // WHY we skip li.first:
    //
    // The first list item has class "first" and contains "Brendlər A-dan Z-yə"
    // which is a brand index page, not a product category. Its href is "" (empty).
    // All other items have href="/collections/slug-with-hash".
    //
    // WHY we use innerText + strip <i>:
    //
    // Each anchor contains the category name and an <i class="icon_right"> element.
    // innerText() includes the icon's text content if any. We strip all content
    // after the newline that separates the text from the icon in the rendered text.
    // -------------------------------------------------------------------------

    @Override
    public List<Category> scrape(String url) {
        Market market = marketRepository.findByName(MARKET_NAME).get();
        List<Category> result = new ArrayList<>();

        try (BrowserContext ctx = playwrightManager.newContext()) {
            Page page = ctx.newPage();

            ctx.route("**/*.{png,jpg,jpeg,gif,webp,ico,woff,woff2,ttf,otf}",
                    route -> route.abort());

            if (!navigateWithRetry(page, url, 3)) {
                log.error("[Omid-Cat] Cannot load homepage");
                return result;
            }

            // Omid has no known popups currently, but attempt dismissal defensively
            dismissPopups(page);

            // Click the menu trigger to ensure AJAX lazy-loading fires
            clickMenuTrigger(page);

            // Wait for the lazy-loaded menu to be populated
            try {
                page.waitForSelector("ul.lazy_vertical_menu.lazyloaded li.verticalmenu-item",
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.ATTACHED)
                                .setTimeout(12_000));
            } catch (Exception e) {
                log.error("[Omid-Cat] Category menu never populated: {}", e.getMessage());
                return result;
            }

            List<ElementHandle> items = page.querySelectorAll(
                    "ul.lazy_vertical_menu li.verticalmenu-item");
            log.info("[Omid-Cat] Found {} menu items", items.size());

            for (ElementHandle item : items) {
                try {
                    // Skip the first item "Brendlər A-dan Z-yə" — it has class "first"
                    // and its href is empty. We check both conditions for robustness.
                    String itemClass = item.getAttribute("class");
                    if (itemClass != null && itemClass.contains("first")) {
                        log.debug("[Omid-Cat] Skipping 'Brendlər A-dan Z-yə' (first item)");
                        continue;
                    }

                    ElementHandle anchor = item.querySelector("a.cms-item-title");
                    if (anchor == null) continue;

                    String href = anchor.getAttribute("href");
                    if (isInvalidLink(href)) continue;

                    // innerText includes the <i> icon's rendered text (usually empty
                    // or just whitespace). We split on newline and take only the first
                    // non-blank line, which is the category name.
                    String rawText = anchor.innerText().trim();
                    String name = extractCategoryName(rawText);
                    if (isBlank(name)) continue;

                    String fullUrl = href.startsWith("http") ? href : BASE_URL + href;

                    // Skip external links
                    if (!fullUrl.startsWith(BASE_URL)) continue;

                    Category cat = persistence.upsertCategory(name, fullUrl, market);
                    result.add(cat);
                    log.info("[Omid-Cat] Category: {} → {}", name, fullUrl);

                } catch (Exception e) {
                    log.warn("[Omid-Cat] Skipping item: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("[Omid-Cat] Scrape failed: {}", e.getMessage(), e);
        }

        log.info("[Omid-Cat] Done — {} categories", result.size());
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Extracts the clean category name from raw innerText.
     *
     * Raw innerText of the anchor looks like:
     *   "Məişət malları\n\n"   (icon has no visible text)
     *   or sometimes just: "Məişət malları"
     *
     * We take the first non-blank line and trim it.
     */
    private String extractCategoryName(String rawText) {
        if (rawText == null) return "";
        for (String line : rawText.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) return trimmed;
        }
        return "";
    }

    /**
     * Clicks the burger menu trigger to ensure the lazy-load AJAX fires.
     * Silently ignores failure — the DOM may already be populated.
     */
    private void clickMenuTrigger(Page page) {
        try {
            page.waitForSelector("div.title_vertical_menu.click",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(5_000));
            page.click("div.title_vertical_menu.click");
            log.debug("[Omid-Cat] Clicked menu trigger");
            sleep(500); // brief wait for AJAX
        } catch (Exception e) {
            log.debug("[Omid-Cat] Menu trigger click skipped: {}", e.getMessage());
        }
    }

    /**
     * Generic popup dismissal — handles any future popups defensively.
     * Currently no known popups exist on omid.az.
     * Adds modal/overlay dismissal patterns used by common Shopify popup apps.
     */
    private void dismissPopups(Page page) {
        // Common Shopify popup patterns — dismiss silently if not present
        String[] popupSelectors = {
                "button.popup-close",
                "button.modal__close",
                "[data-remodal-action='close']",
                ".klaviyo-close-form",
                "#popup-modal button.close",
                "button.needsclick.kl-private-reset-css-Xuajs1"
        };

        for (String selector : popupSelectors) {
            try {
                page.waitForSelector(selector,
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(1_500));
                page.click(selector, new Page.ClickOptions().setTimeout(1_000));
                log.info("[Omid-Cat] Dismissed popup: {}", selector);
                break;
            } catch (Exception ignored) {}
        }
    }

    private boolean isInvalidLink(String href) {
        if (href == null || href.isBlank()) return true;
        if (href.equals("#"))              return true;
        if (href.contains("javascript:")) return true;
        return false;
    }

    private boolean navigateWithRetry(Page page, String url, int maxAttempts) {
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(NAV_TIMEOUT));
                return true;
            } catch (Exception e) {
                log.warn("[Omid-Cat] Navigate attempt {}/{}: {}", i, maxAttempts, e.getMessage());
                if (i < maxAttempts) sleep((long) Math.pow(2, i) * 1_000);
            }
        }
        return false;
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}