package com.teztap.service.scraper;


import com.teztap.model.Category;
import com.teztap.service.scraper.araz.ArazCategoryScraper;
import com.teztap.service.scraper.araz.ArazProductScraper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@EnableAsync
public class ScraperRunnerService {

    private final ArazCategoryScraper categoryScraper;
    private final ArazProductScraper productScraper;

    public ScraperRunnerService(ArazCategoryScraper categoryScraper,
                                ArazProductScraper productScraper) {
        this.categoryScraper = categoryScraper;
        this.productScraper = productScraper;
    }

    // Runs every x minute
    @Scheduled(fixedRate = 60 * 60 * 1000)
    @Async
    public void runScrapers() {
        System.out.println("Starting category scraper...");
        List<Category> categories = categoryScraper.scrape("https://www.arazmarket.az/az");
        System.out.println("Categories scraped: " + categories.size());


        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        for (Category cat : categories) {
            System.out.println("Scraping products for category: " + cat.getName());
            productScraper.scrape(cat.getUrl());
        }

        System.out.println("All scraping finished.");
    }
}
