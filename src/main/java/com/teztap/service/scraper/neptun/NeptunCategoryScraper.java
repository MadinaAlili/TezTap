package com.teztap.service.scraper.neptun;

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
public class NeptunCategoryScraper implements Scraper<List<Category>> {

    private static final Logger log = LoggerFactory.getLogger(NeptunCategoryScraper.class);

    private static final String BASE_URL    = "https://neptun.az";
    private static final String MARKET_NAME = "NEPTUN";
    private static final int    NAV_TIMEOUT = 30_000;

    private final PlaywrightManager         playwrightManager;
    private final ScraperPersistenceService persistence;
    private final MarketRepository          marketRepository;

    @Override
    public List<Category> scrape(String url) {
        Market market = marketRepository.findByName(MARKET_NAME).get();
        List<Category> result = new ArrayList<>();

        try (BrowserContext ctx = playwrightManager.newContext()) {
            Page page = ctx.newPage();

            // Neptune is OpenCart (server-rendered PHP) — block media only
            ctx.route("**/*.{png,jpg,jpeg,gif,webp,ico,woff,woff2,ttf,otf}",
                    route -> route.abort());

            if (!navigateWithRetry(page, url, 3)) {
                log.error("[Neptun-Cat] Cannot load homepage");
                return result;
            }

            // OpenCart renders everything server-side so DOMCONTENTLOADED is sufficient.
            // We still wait for the megamenu to confirm the page is usable.
            try {
                page.waitForSelector("ul.megamenu",
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.ATTACHED)
                                .setTimeout(15_000));
            } catch (Exception e) {
                log.error("[Neptun-Cat] Megamenu not found: {}", e.getMessage());
                return result;
            }

            // WHY a.main-menu is stable:
            //   Neptun uses OpenCart — a PHP CMS with hand-written CSS class names.
            //   These are NOT CSS modules and do NOT contain auto-generated hashes.
            //   They change only when the theme is manually edited.
            List<ElementHandle> links = page.querySelectorAll("a.main-menu");
            log.info("[Neptun-Cat] Found {} category links", links.size());

            for (ElementHandle anchor : links) {
                try {
                    String name = anchor.innerText().trim();
                    String href = anchor.getAttribute("href");

                    if (isInvalidLink(name, href)) continue;

                    // Make absolute if relative
                    String fullUrl = href.startsWith("http") ? href : BASE_URL + href;

                    // Exclude external partner links (electronics redirects to soliton.az)
                    if (!fullUrl.startsWith(BASE_URL)) continue;

                    Category cat = persistence.upsertCategory(name, fullUrl, market);
                    result.add(cat);
                    log.info("[Neptun-Cat] Category: {} → {}", name, fullUrl);

                } catch (Exception e) {
                    log.warn("[Neptun-Cat] Skipping link: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("[Neptun-Cat] Scrape failed: {}", e.getMessage(), e);
        }

        log.info("[Neptun-Cat] Done — {} categories", result.size());
        return result;
    }

    private boolean isInvalidLink(String name, String href) {
        if (name == null || name.isBlank()) return true;
        if (href == null || href.isBlank()) return true;
        if (href.contains("javascript:"))  return true;
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
                log.warn("[Neptun-Cat] Navigate attempt {}/{}: {}", i, maxAttempts, e.getMessage());
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