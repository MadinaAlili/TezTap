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
                "https://www.arazmarket.az/az",
                "https://www.arazmarket.az/_next/static/media/logo.42b6a354.svg",
                "Araz Market"
        );

        createMarketIfNotExists(
                "BAZARSTORE",
                "https://bazarstore.az/",
                "https://bazarstore.az/",
                "https://bazarstore.az/cdn/shop/files/Bazarstore-az-logo_250x.svg?v=1695124921",
                "Bazarstore"
        );

        createMarketIfNotExists(
                "NEPTUN",
                "https://neptun.az/",
                "https://neptun.az/",
                "https://imageproxy.wolt.com/mes-image/abdbbfe9-99e8-49a4-90a5-e0d5ece4de8b/7beae471-22e8-428d-a154-a682aa7348fb",
                "Neptun Market"
        );

        createMarketIfNotExists(
                "OMID",
                "https://omid.az/",
                "https://omid.az/",
                "https://omid.az/cdn/shop/files/ONLINE-LOGO_170x@2x.png?v=1748948021",
                "Omid"
        );
        createMarketIfNotExists(
                "TAMSTORE",
                "https://www.tamstore.az/az",
                "https://www.tamstore.az/az",
                "https://www.tamstore.az/uploads/1703078675-6582eb13d78b9.png",
                "Tamstore"
        );

        createMarketIfNotExists(
                "APLUS",
                "https://www.aplus.az/",
                "https://www.aplus.az/",
                "https://api.aplus.az/storage/RcgGZfSi3kds5Nfs0p2EQ6ocn9UxkqcFUrkPA6CK.png",
                "Aplus"
        );

        createMarketIfNotExists(
                "ALMARKET",
                "https://almarket.az/az/anasehife",
                "https://almarket.az/az/anasehife",
                "https://almarket.az/assets/img/icons/logo.svg",
                "Almarket"
        );

        createMarketIfNotExists(
                "RAHAT",
                "https://rahatmarket.az/az/index",
                "https://rahatmarket.az/az/index",
                "https://rahatmarket.az/storage/1/rahat_supermarket.svg",
                "Rahat Market"
        );
    }

    private void createMarketIfNotExists(String name, String baseUrl, String categoryUrl, String logoUrl, String displayName) {
        boolean exists = marketRepository.existsByNameOrBaseUrl(name, baseUrl);

        if (!exists) {
            Market market = new Market();
            market.setLogoUrl(logoUrl);
            market.setDisplayName(displayName);
            market.setName(name);
            market.setBaseUrl(baseUrl);
            market.setCategoryScrapingBaseUrl(categoryUrl);
            market.setActive(true);

            marketRepository.save(market);
        }
    }
}