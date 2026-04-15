package com.teztap.model;

import com.teztap.repository.MarketBranchRepository;
import com.teztap.repository.MarketRepository;
import com.teztap.service.GeometryUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketBranchSeeder implements CommandLineRunner {

    private final MarketBranchRepository branchRepository;
    private final MarketRepository marketRepository;
    private final Random random = new Random();

    @Override
    public void run(String... args) {
//        if (branchRepository.count() == 0) {
//            log.info("MarketBranch table is empty. Seeding random data...");
//            seedBranches();
//        }
    }

    private void seedBranches() {
        // Fetch the two existing markets (IDs 1 and 2)
        Market market1 = marketRepository.findById(1L).orElse(null);
        Market market2 = marketRepository.findById(2L).orElse(null);

        if (market1 == null || market2 == null) {
            log.error("Markets with ID 1 and 2 not found. Seeding aborted.");
            return;
        }

        List<Market> markets = List.of(market1, market2);

        String[] branchSuffixes = {"Express", "Hyper", "Super", "24/7", "Premium", "Discount"};
        String[] cities = {"Baku", "Sumqayit", "Ganja"};
        String[] districts = {"Nasimi", "Narimanov", "Yasamal", "Sabail", "Khatai", "Binagadi"};

        for (int i = 0; i < 10; i++) {
            Market parentMarket = markets.get(random.nextInt(markets.size()));
            String branchName = parentMarket.getName() + " " + branchSuffixes[random.nextInt(branchSuffixes.length)] + " " + (i + 1);

            // 1. Create the Embedded Address object
            Address branchAddress = new Address();

            // Randomly choose location details
            String city = cities[random.nextInt(cities.length)];
            String district = districts[random.nextInt(districts.length)];

//            branchAddress.setCity(city);
//            branchAddress.setDistrict(district);
            branchAddress.setFullAddress("Street No: " + (random.nextInt(150) + 1) + ", near " + district + " station");
            branchAddress.setAdditionalInfo("Entrance from the back side");

            // Generate random coordinates (approx. Baku region)
            BigDecimal lng = BigDecimal.valueOf(49.8 + (random.nextDouble() * 0.1));
            BigDecimal lat = BigDecimal.valueOf(40.3 + (random.nextDouble() * 0.1));
            branchAddress.setLocation(GeometryUtils.createPoint(lng, lat));

            // 2. Build the MarketBranch
            MarketBranch branch = new MarketBranch();
            branch.setName(branchName);
            branch.setDescription("Fresh groceries and daily essentials at " + branchName);
            branch.setMarket(parentMarket);

            // Set the embedded address
            branch.setAddress(branchAddress);

            branchRepository.save(branch);
        }

        log.info("Successfully seeded 10 Market Branches with Embedded Addresses.");
    }
}