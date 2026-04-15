package com.teztap.model;

import com.teztap.repository.MarketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class MarketSeeder implements CommandLineRunner {

    private final MarketRepository marketRepository;

    @Override
    public void run(String... args) {
        createMarketIfNotExists(
                "ARAZ",
                "https://www.arazmarket.az",
                "https://www.arazmarket.az/az"
        );

        createMarketIfNotExists(
                "BAZARSTORE",
                "https://bazarstore.az/",
                "https://bazarstore.az/"
        );

        createMarketIfNotExists(
                "NEPTUN",
                "https://neptun.az/",
                "https://neptun.az/"
        );

        createMarketIfNotExists(
                "OMID",
                "https://omid.az/",
                "https://omid.az/"
        );
    }

    private void createMarketIfNotExists(String name, String baseUrl, String categoryUrl) {
        boolean exists = marketRepository.existsByNameOrBaseUrl(name, baseUrl);

        if (!exists) {
            Market market = new Market();
            market.setName(name);
            market.setBaseUrl(baseUrl);
            market.setCategoryScrapingBaseUrl(categoryUrl);
            market.setActive(true);

            marketRepository.save(market);
        }
    }
}