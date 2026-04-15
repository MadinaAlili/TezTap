package com.teztap.service;

import com.google.maps.GeoApiContext;
import com.google.maps.PlaceDetailsRequest;
import com.google.maps.PlacesApi;
import com.google.maps.model.PlaceDetails;
import com.google.maps.model.PlacesSearchResponse;
import com.google.maps.model.PlacesSearchResult;
import com.teztap.model.Address;
import com.teztap.model.Market;
import com.teztap.model.MarketBranch;
import com.teztap.model.TimeRange;
import com.teztap.repository.MarketBranchRepository;
import com.teztap.repository.MarketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketSynchronizationService {

    private final MarketBranchRepository branchRepository;
    private final MarketRepository marketRepository;

    @Value("${google.maps.api-key}")
    private String apiKey;

    @Value("${app.markets.sync.enabled:false}")
    private boolean syncEnabled;

    @Value("${app.markets.export.enabled:true}")
    private boolean exportEnabled;

    // 1. NON-BLOCKING STARTUP
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationStartup() {
        if (!syncEnabled) {
            log.info("Market synchronization is disabled.");
            return;
        }

        if (branchRepository.count() > 0) {
            log.info("Database already contains branches. Skipping sync.");
            if (exportEnabled) exportBranchesToJson();
            return;
        }

        log.info("Kicking off Google Maps synchronization in the background...");

        // CompletableFuture runs this in a separate thread pool.
        // Your Spring Boot app immediately finishes starting up and becomes healthy/ready.
        CompletableFuture.runAsync(this::executeSynchronization)
                .exceptionally(ex -> {
                    log.error("Fatal error during background synchronization", ex);
                    return null;
                });
    }

    // 2. NO TOP-LEVEL @Transactional
    private void executeSynchronization() {
        try{
        Market arazMarket = marketRepository.findByName("ARAZ").get();
        fetchAndSaveBranches("Araz market", arazMarket);
        }catch(Exception e){
            System.err.println("[MarketSynchronizationService] Araz market google maps search or db fetch failed");
        }
        try{
            Market bazarstoreMarket = marketRepository.findByName("BAZARSTORE").get();
            fetchAndSaveBranches("Bazarstore market", bazarstoreMarket);
        }catch(Exception e){
            System.err.println("[MarketSynchronizationService] Bazarstore market google maps search or db fetch failed");
        }
        try{
            Market neptunMarket = marketRepository.findByName("NEPTUN").get();
            fetchAndSaveBranches("Neptun market", neptunMarket);
        }catch(Exception e){
            System.err.println("[MarketSynchronizationService] Neptun market google maps search or db fetch failed");
        }

        if (exportEnabled) exportBranchesToJson();
    }

    private void fetchAndSaveBranches(String query, Market market) {
        // Configure retry behavior within the Google Client itself
        GeoApiContext context = new GeoApiContext.Builder()
                .apiKey(apiKey)
                .maxRetries(3) // Robustness: Auto-retry on network blips
                .build();

        try {
            PlacesSearchResponse response = PlacesApi.textSearchQuery(context, query).await();

            for (PlacesSearchResult result : response.results) {
                if (branchRepository.existsByGooglePlaceId(result.placeId)) continue;

                try {
                    // 3. RATE LIMITING PROTECTION
                    // A 200ms pause prevents slamming Google's API and getting blocked
                    Thread.sleep(200);

                    PlaceDetails details = PlacesApi.placeDetails(context, result.placeId)
                            .fields(
                                    PlaceDetailsRequest.FieldMask.PLACE_ID,
                                    PlaceDetailsRequest.FieldMask.NAME,
                                    PlaceDetailsRequest.FieldMask.FORMATTED_PHONE_NUMBER,
                                    PlaceDetailsRequest.FieldMask.BUSINESS_STATUS,
                                    PlaceDetailsRequest.FieldMask.FORMATTED_ADDRESS,
                                    PlaceDetailsRequest.FieldMask.GEOMETRY_LOCATION,
                                    PlaceDetailsRequest.FieldMask.PLUS_CODE,
                                    PlaceDetailsRequest.FieldMask.OPENING_HOURS
                            )
                            .await();

                    saveSingleBranch(details, market);

                } catch (Exception e) {
                    // If one specific branch fails, log it and move to the next.
                    // DO NOT crash the entire loop.
                    log.error("Failed to process placeId {}: {}", result.placeId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to execute Maps Search Query: ", e);
        } finally {
            context.shutdown();
        }
    }

    private void saveSingleBranch(PlaceDetails details, Market market) {
        MarketBranch branch = new MarketBranch();
        branch.setMarket(market);
        branch.setGooglePlaceId(details.placeId);

        // 4. NULL SAFETY (Defensive Programming)
        branch.setName(details.name != null ? details.name : "Unknown Branch");
        branch.setPhoneNumber(details.formattedPhoneNumber);
        branch.setDescription(details.businessStatus != null ? details.businessStatus : "Operational");

        Address address = new Address();
        address.setFullAddress(details.formattedAddress);

        if (details.geometry != null && details.geometry.location != null) {
            address.setLocation(GeometryUtils.createPoint(
                    BigDecimal.valueOf(details.geometry.location.lng),
                    BigDecimal.valueOf(details.geometry.location.lat)
            ));
        } else {
            // If Google returns no coordinates, we cannot use this for delivery. Throwing an
            // exception here skips this branch but keeps the loop running.
            throw new IllegalStateException("Place has no geographic coordinates");
        }

        branch.setAddress(address);
        branch.setPlusCode(details.plusCode != null ? details.plusCode.globalCode : "UNKNOWN");

        mapOpeningHours(details, branch);

        // The save method handles its own microtransaction automatically.
        branchRepository.save(branch);
        log.info("Saved new branch: {}", branch.getName());
    }

    private void mapOpeningHours(PlaceDetails details, MarketBranch branch) {
        if (details.openingHours == null || details.openingHours.periods == null) {
            branch.setOpen24_7(false);
            branch.setOpeningHours(new HashMap<>());
            return;
        }

        if (details.openingHours.periods.length == 1 &&
                details.openingHours.periods[0].open.day == com.google.maps.model.OpeningHours.Period.OpenClose.DayOfWeek.SUNDAY &&
                details.openingHours.periods[0].open.time.equals(LocalTime.MIDNIGHT) &&
                details.openingHours.periods[0].close == null) {

            branch.setOpen24_7(true);
            branch.setOpeningHours(new HashMap<>());
            return;
        }

        branch.setOpen24_7(false);
        Map<DayOfWeek, List<TimeRange>> schedule = new EnumMap<>(DayOfWeek.class);
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");

        for (var period : details.openingHours.periods) {
            if (period.open != null && period.close != null) {
                DayOfWeek day = mapGoogleDayToJavaDay(period.open.day);

                String openTime = period.open.time.format(timeFormatter);
                String closeTime = period.close.time.format(timeFormatter);

                schedule.computeIfAbsent(day, k -> new ArrayList<>())
                        .add(new TimeRange(openTime, closeTime));
            }
        }
        branch.setOpeningHours(schedule);
    }

    private DayOfWeek mapGoogleDayToJavaDay(com.google.maps.model.OpeningHours.Period.OpenClose.DayOfWeek googleDay) {
        return switch (googleDay) {
            case SUNDAY -> DayOfWeek.SUNDAY;
            case MONDAY -> DayOfWeek.MONDAY;
            case TUESDAY -> DayOfWeek.TUESDAY;
            case WEDNESDAY -> DayOfWeek.WEDNESDAY;
            case THURSDAY -> DayOfWeek.THURSDAY;
            case FRIDAY -> DayOfWeek.FRIDAY;
            case SATURDAY -> DayOfWeek.SATURDAY;
            case UNKNOWN -> DayOfWeek.MONDAY;
        };
    }

    private void exportBranchesToJson() {
        log.info("Starting export of MarketBranches to JSON...");
        List<MarketBranch> allBranches = branchRepository.findAll();
        try {
            // 1. Use the Jackson 3 Builder to create the ObjectMapper
            ObjectMapper mapper = JsonMapper.builder()
                    .findAndAddModules() // 2. This replaces findAndRegisterModules()
                    .build();            // 3. Lock it down to an immutable mapper

            File outputFile = new File("market_branches_export.json");

            // 4. writerWithDefaultPrettyPrinter() still exists in Jackson 3 and works perfectly
            mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, allBranches);
            log.info("Successfully exported {} branches to {}", allBranches.size(), outputFile.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to export branches to JSON", e);
        }
    }
}