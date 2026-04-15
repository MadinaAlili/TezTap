package com.teztap.service.scraper.bazarstore;

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
public class BazarstoreCategoryScraper implements Scraper<List<Category>> {

    private static final Logger log = LoggerFactory.getLogger(BazarstoreCategoryScraper.class);

    private static final String BASE_URL    = "https://bazarstore.az";
    private static final String MARKET_NAME = "BAZARSTORE";
    private static final int    NAV_TIMEOUT = 30_000;

    private final PlaywrightManager         playwrightManager;
    private final ScraperPersistenceService persistence;
    private final MarketRepository          marketRepository;

    // -------------------------------------------------------------------------
    // WHY these selectors are stable:
    //
    // Bazarstore runs on Shopify. Shopify themes use hand-written class names
    // in Liquid templates — NOT CSS Modules. Classes like "site-cat__link--main"
    // and "site-cat--has-dropdown" are defined in the theme's CSS file and only
    // change if the theme developer manually renames them.
    //
    // The category menu is server-rendered (HTML is in the initial response),
    // so NO click or interaction is required to make it appear.
    //
    // Parent category structure:
    //   li.site-cat--has-dropdown > a.site-cat__link--main  ← has sub-categories
    //   li (plain)               > a.site-cat__link--main   ← leaf (no dropdown)
    //
    // Both are captured by: a.site-cat__link--main
    // -------------------------------------------------------------------------

    @Override
    public List<Category> scrape(String url) {
        Market market = marketRepository.findByName(MARKET_NAME).get();
        List<Category> result = new ArrayList<>();

        try (BrowserContext ctx = playwrightManager.newContext()) {
            Page page = ctx.newPage();

            // Block media — Shopify theme CSS/JS is needed for DOM, but images are not
            ctx.route("**/*.{png,jpg,jpeg,gif,webp,ico,woff,woff2,ttf,otf}",
                    route -> route.abort());

            if (!navigateWithRetry(page, url, 3)) {
                log.error("[Bazar-Cat] Cannot load homepage");
                return result;
            }

            // Shopify pages are server-rendered; wait for the nav to be present
            try {
                page.waitForSelector("ul#header-SiteCat",
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.ATTACHED)
                                .setTimeout(12_000));
            } catch (Exception e) {
                log.error("[Bazar-Cat] Category nav not found: {}", e.getMessage());
                return result;
            }

            // Dismiss both known popup types before interacting
            dismissPopups(page);

            // All parent category anchors — one selector covers both dropdown and leaf
            List<ElementHandle> anchors = page.querySelectorAll(
                    "ul#header-SiteCat a.site-cat__link--main");
            log.info("[Bazar-Cat] Found {} category links", anchors.size());

            for (ElementHandle anchor : anchors) {
                try {
                    // innerText strips emoji and leading/trailing whitespace
                    String name = anchor.innerText().trim();
                    // Remove emoji prefix (e.g. "🍉 Meyvə, Tərəvəz" → "Meyvə, Tərəvəz")
                    name = name.replaceAll("^[\\p{So}\\p{Sm}\\p{Sk}\\s]+", "").trim();

                    String href = anchor.getAttribute("href");

                    if (isInvalidLink(name, href)) continue;

                    // href may already be absolute (Shopify sometimes inlines full URLs)
                    String fullUrl = href.startsWith("http") ? href : BASE_URL + href;

                    // Skip external URLs (e.g. myshopify.com subdomain)
                    if (!fullUrl.startsWith(BASE_URL)) continue;

                    Category cat = persistence.upsertCategory(name, fullUrl, market);
                    result.add(cat);
                    log.info("[Bazar-Cat] Category: {} → {}", name, fullUrl);

                } catch (Exception e) {
                    log.warn("[Bazar-Cat] Skipping link: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("[Bazar-Cat] Scrape failed: {}", e.getMessage(), e);
        }

        log.info("[Bazar-Cat] Done — {} categories", result.size());
        return result;
    }

    // ── Popup dismissal ───────────────────────────────────────────────────────

    /**
     * Bazarstore has two known popup types:
     *
     * 1. PushOwl notification permission dialog (dialog.pushowl-optin)
     *    Close with: button.js-pushowl-no-button
     *
     * 2. Pop-Convert promotional modal (#pc-modal-backdrop)
     *    Close with: #pc-main-close-button
     *
     * Each is handled independently — failure on one never prevents the other.
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
                            .setTimeout(5_000));
            try {
                page.click("button.js-pushowl-no-button",
                        new Page.ClickOptions().setTimeout(3_000));
                log.info("[Bazar-Cat] Dismissed PushOwl dialog");
            } catch (Exception e) {
                // JS removal as fallback
                page.evaluate("document.querySelector('dialog.pushowl-optin')?.remove()");
                log.info("[Bazar-Cat] Removed PushOwl dialog via JS");
            }
        } catch (Exception e) {
            log.debug("[Bazar-Cat] No PushOwl dialog ({})", e.getMessage());
        }
    }

    private void dismissPopConvert(Page page) {
        try {
            page.waitForSelector("#pc-modal-backdrop",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(5_000));
            try {
                page.click("#pc-main-close-button",
                        new Page.ClickOptions().setTimeout(3_000));
                log.info("[Bazar-Cat] Dismissed Pop-Convert modal");
            } catch (Exception e) {
                page.evaluate("document.getElementById('pc-modal-backdrop')?.remove()");
                log.info("[Bazar-Cat] Removed Pop-Convert modal via JS");
            }
        } catch (Exception e) {
            log.debug("[Bazar-Cat] No Pop-Convert modal ({})", e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isInvalidLink(String name, String href) {
        if (name == null || name.isBlank()) return true;
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
                log.warn("[Bazar-Cat] Navigate attempt {}/{}: {}", i, maxAttempts, e.getMessage());
                if (i < maxAttempts) sleep((long) Math.pow(2, i) * 1_000);
            }
        }
        return false;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
