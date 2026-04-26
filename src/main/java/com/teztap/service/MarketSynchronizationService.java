package com.teztap.service;

import com.google.maps.GeoApiContext;
import com.google.maps.PlaceDetailsRequest;
import com.google.maps.PlacesApi;
import com.google.maps.model.LatLng;
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

    // Baku city center — used as location bias anchor
    private static final LatLng BAKU_CENTER = new LatLng(40.4093, 49.8671);

    // ~150 km radius covers all of Azerbaijan
    private static final int AZERBAIJAN_RADIUS_METERS = 50_000;

    // Ordered list of all markets to sync: [DB enum name, human query term]
    private static final List<String[]> MARKETS = List.of(
            new String[]{"ARAZ",       "Araz market"},
            new String[]{"BAZARSTORE", "Bazarstore market"},
            new String[]{"NEPTUN",     "Neptun market"},
            new String[]{"ALMARKET",   "Almarket market"},
            new String[]{"OMID",       "Omid market"},
            new String[]{"APLUS",      "Aplus market"},
            new String[]{"TAMSTORE",   "Tamstore market"},
            new String[]{"RAHAT",      "Rahat market"}
    );

    // -------------------------------------------------------------------------
    // Startup
    // -------------------------------------------------------------------------

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

        CompletableFuture.runAsync(this::executeSynchronization)
                .exceptionally(ex -> {
                    log.error("Fatal error during background synchronization", ex);
                    return null;
                });
    }

    // -------------------------------------------------------------------------
    // Orchestration
    // -------------------------------------------------------------------------

    private void executeSynchronization() {
        for (String[] entry : MARKETS) {
            String dbName    = entry[0];
            String queryTerm = entry[1];

            try {
                Market market = marketRepository.findByName(dbName).get();
                // Append "Azerbaijan" so the text query itself is geo-scoped,
                // combined with location bias below for double filtering.
                fetchAndSaveBranches(queryTerm + " Azerbaijan", market);
            } catch (Exception e) {
                log.error("[MarketSync] {} market sync failed: {}", dbName, e.getMessage());
            }
        }

        if (exportEnabled) exportBranchesToJson();
    }

    // -------------------------------------------------------------------------
    // Fetch & persist
    // -------------------------------------------------------------------------

    private void fetchAndSaveBranches(String query, Market market) {
        GeoApiContext context = new GeoApiContext.Builder()
                .apiKey(apiKey)
                .maxRetries(3)
                .build();

        try {
            PlacesSearchResponse response = PlacesApi
                    .textSearchQuery(context, query)
                    // Location bias: ranks results within this radius higher.
                    // Combined with the "Azerbaijan" query term this is very tight.
                    .location(BAKU_CENTER)
                    .radius(AZERBAIJAN_RADIUS_METERS)
                    .await();

            for (PlacesSearchResult result : response.results) {
                if (branchRepository.existsByGooglePlaceId(result.placeId)) continue;

                // Pre-filter using the lightweight search result's vicinity
                // BEFORE spending a quota-expensive PlaceDetails API call.
                if (!isSearchResultInAzerbaijan(result)) {
                    log.warn("Skipping out-of-country search result: {} ({})",
                            result.name, result.vicinity);
                    continue;
                }

                try {
                    Thread.sleep(200); // Respect Google rate limits

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
                    log.error("Failed to process placeId {}: {}", result.placeId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to execute Maps Search Query '{}': ", query, e);
        } finally {
            context.shutdown();
        }
    }

    /**
     * Pre-filter on the lightweight search result (no extra API call needed).
     * PlacesSearchResult.vicinity is a short address like "Baku, Azerbaijan".
     * If vicinity is null we give the benefit of the doubt and let the
     * PlaceDetails formatted address be the final word.
     */
    private boolean isSearchResultInAzerbaijan(PlacesSearchResult result) {
        if (result.vicinity == null) return true;
        String v = result.vicinity.toLowerCase();
        return v.contains("azerbaijan") || v.contains("azərbaycan");
    }

    // -------------------------------------------------------------------------
    // Entity mapping
    // -------------------------------------------------------------------------

    private void saveSingleBranch(PlaceDetails details, Market market) {
        MarketBranch branch = new MarketBranch();
        branch.setMarket(market);
        branch.setGooglePlaceId(details.placeId);
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
            throw new IllegalStateException("Place has no geographic coordinates");
        }

        branch.setAddress(address);
        branch.setPlusCode(details.plusCode != null ? details.plusCode.globalCode : "UNKNOWN");

        mapOpeningHours(details, branch);

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
                String openTime  = period.open.time.format(timeFormatter);
                String closeTime = period.close.time.format(timeFormatter);
                schedule.computeIfAbsent(day, k -> new ArrayList<>())
                        .add(new TimeRange(openTime, closeTime));
            }
        }
        branch.setOpeningHours(schedule);
    }

    private DayOfWeek mapGoogleDayToJavaDay(
            com.google.maps.model.OpeningHours.Period.OpenClose.DayOfWeek googleDay) {
        return switch (googleDay) {
            case SUNDAY    -> DayOfWeek.SUNDAY;
            case MONDAY    -> DayOfWeek.MONDAY;
            case TUESDAY   -> DayOfWeek.TUESDAY;
            case WEDNESDAY -> DayOfWeek.WEDNESDAY;
            case THURSDAY  -> DayOfWeek.THURSDAY;
            case FRIDAY    -> DayOfWeek.FRIDAY;
            case SATURDAY  -> DayOfWeek.SATURDAY;
            case UNKNOWN   -> DayOfWeek.MONDAY;
        };
    }

    // -------------------------------------------------------------------------
    // Export
    // -------------------------------------------------------------------------

    private void exportBranchesToJson() {
        log.info("Starting export of MarketBranches to JSON...");
        List<MarketBranch> allBranches = branchRepository.findAll();
        try {
            ObjectMapper mapper = JsonMapper.builder()
                    .findAndAddModules()
                    .build();

            File outputFile = new File("market_branches_export.json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, allBranches);
            log.info("Successfully exported {} branches to {}",
                    allBranches.size(), outputFile.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to export branches to JSON", e);
        }
    }
}
