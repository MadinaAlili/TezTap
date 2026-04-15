package com.teztap.service;


import com.teztap.dto.RouteInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Fetches road distance and travel time from OpenRouteService (ORS).
 *
 * Free tier: 2,000 requests/day — plenty for a small app.
 * Sign up at https://openrouteservice.org/dev/#/signup and set ors.api.key in application.properties.
 *
 * On any failure (network, quota, etc.) it transparently falls back to a
 * Haversine straight-line estimate so pricing never hard-fails.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingService {

    private final RestTemplate restTemplate;

    @Value("${ors.api.key}")
    private String apiKey;

    private static final String ORS_DIRECTIONS_URL =
            "https://api.openrouteservice.org/v2/directions/driving-car";

    /**
     * Returns the driving route between two coordinates.
     *
     * ORS expects [longitude, latitude] order (GeoJSON convention).
     * Coordinates:  from = market location,  to = customer location.
     */
    public RouteInfo getRoute(double fromLat, double fromLng, double toLat, double toLng) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", apiKey);   // ORS uses the key as a bare header

        // ORS body: coordinates as [[lng, lat], [lng, lat]]
        Map<String, Object> body = Map.of(
                "coordinates", List.of(
                        List.of(fromLng, fromLat),
                        List.of(toLng,   toLat)
                )
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    ORS_DIRECTIONS_URL, HttpMethod.POST, request, JsonNode.class
            );

            JsonNode route = response.getBody()
                    .path("routes")
                    .get(0);                         // first (and only) route

            double distanceMeters = route.path("summary").path("distance").asDouble();
            double durationSeconds = route.path("summary").path("duration").asDouble();
            // ORS returns a Polyline-encoded geometry for map display
            String polyline = route.path("geometry").asText("");

            log.debug("ORS route: {:.2f} km, {:.1f} min",
                    distanceMeters / 1000.0, durationSeconds / 60.0);

            return new RouteInfo(
                    distanceMeters / 1000.0,    // metres → km
                    durationSeconds / 60.0,     // seconds → minutes
                    polyline
            );

        } catch (Exception ex) {
            log.warn("ORS request failed ({}), falling back to Haversine", ex.getMessage());
            return haversineFallback(fromLat, fromLng, toLat, toLng);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fallback: straight-line distance (Haversine formula)
    // Adds a 1.35× road-winding factor and a rough speed estimate for time.
    // ─────────────────────────────────────────────────────────────────────────

    private RouteInfo haversineFallback(double lat1, double lon1, double lat2, double lon2) {
        double straightKm = haversineKm(lat1, lon1, lat2, lon2);
        double roadKm     = straightKm * 1.35;          // ~35% longer on real roads
        double minutes    = (roadKm / 30.0) * 60.0;     // assume avg 30 km/h in city
        log.debug("Haversine fallback: {:.2f} km straight → {:.2f} km road estimate", straightKm, roadKm);
        return new RouteInfo(roadKm, minutes, "");       // no polyline on fallback
    }

    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final int EARTH_RADIUS_KM = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}