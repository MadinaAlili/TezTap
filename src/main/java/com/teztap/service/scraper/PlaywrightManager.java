package com.teztap.service.scraper;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PlaywrightManager {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightManager.class);

    @Value("${playwright.headless:true}")
    private boolean headless;

    private Playwright playwright;
    private Browser   browser;

    @PostConstruct
    public void init() {
        launchBrowser();
    }

    private void launchBrowser() {
        log.info("[Playwright] Launching Chromium (headless={})...", headless);
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(headless)
                        .setArgs(List.of(
                                "--no-sandbox",
                                "--disable-dev-shm-usage",   // critical in containers with small /dev/shm
                                "--disable-gpu",
                                "--disable-extensions",
                                "--disable-background-networking",
                                "--disable-sync",
                                "--mute-audio",
                                "--window-size=1280,800"
                        ))
        );
        log.info("[Playwright] Browser ready.");
    }

    /**
     * Returns a fresh, isolated BrowserContext. Always close via try-with-resources.
     * If the browser has crashed it is automatically relaunched before returning.
     */
    public synchronized BrowserContext newContext() {
        if (browser == null || !browser.isConnected()) {
            log.warn("[Playwright] Browser disconnected — relaunching.");
            closeSilently();
            launchBrowser();
        }
        BrowserContext ctx = browser.newContext(
                new Browser.NewContextOptions()
                        .setViewportSize(1280, 800)
                        .setUserAgent(
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                        "Chrome/124.0.0.0 Safari/537.36"
                        )
        );
        // Apply timeouts at context level — covers ALL operations including
        // ElementHandle.getAttribute(), innerText() etc. which ignore page-level timeouts.
        ctx.setDefaultTimeout(20_000);
        ctx.setDefaultNavigationTimeout(30_000);
        return ctx;
    }

    @PreDestroy
    public void closeSilently() {
        try { if (browser    != null) browser.close();    } catch (Exception ignored) {}
        try { if (playwright != null) playwright.close(); } catch (Exception ignored) {}
        browser    = null;
        playwright = null;
    }
}