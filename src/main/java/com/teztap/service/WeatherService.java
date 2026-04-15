package com.teztap.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Fetches current weather from OpenWeatherMap and maps it to a WeatherCondition.
 *
 * Free tier: 1,000 calls/day.  Results are cached for 5 minutes (configured in
 * AppConfig) so a busy app won't burn through the quota.
 *
 * Sign up at https://openweathermap.org/api and set openweather.api.key.
 *
 * OWM weather condition ID ranges (see https://openweathermap.org/weather-conditions):
 *   2xx → Thunderstorm
 *   3xx → Drizzle  (treated as rain)
 *   5xx → Rain
 *   6xx → Snow
 *   7xx → Atmosphere (fog, haze) — treated as clear for pricing
 *   800 → Clear sky
 *   80x → Clouds — treated as clear for pricing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherService {

    private final RestTemplate restTemplate;

    @Value("${owm.api.key}")
    private String apiKey;

    private static final String OWM_URL =
            "https://api.openweathermap.org/data/2.5/weather?lat={lat}&lon={lon}&appid={key}";

    public enum WeatherCondition {
        CLEAR,
        RAIN,
        SNOW,
        STORM
    }

    /**
     * Returns the current weather condition at the given coordinates.
     *
     * Cache key combines lat+lon rounded to 2 decimal places (~1 km grid)
     * so nearby requests share a cached value without per-meter granularity.
     * The "weather" cache is configured with a 5-minute TTL in AppConfig.
     */
    @Cacheable(value = "weather", key = "T(Math).round(#lat * 100) + ',' + T(Math).round(#lon * 100)")
    public WeatherCondition getCurrentCondition(double lat, double lon) {
        try {
            JsonNode data = restTemplate.getForObject(OWM_URL, JsonNode.class, lat, lon, apiKey);

            int weatherId = data.path("weather").get(0).path("id").asInt(800);
            WeatherCondition condition = mapCondition(weatherId);

            log.debug("Weather at ({}, {}): id={} → {}", lat, lon, weatherId, condition);
            return condition;

        } catch (Exception ex) {
            log.warn("OpenWeatherMap request failed ({}), defaulting to CLEAR", ex.getMessage());
            return WeatherCondition.CLEAR;   // no penalty on failure
        }
    }

    private WeatherCondition mapCondition(int id) {
        if (id >= 200 && id < 300) return WeatherCondition.STORM;
        if (id >= 300 && id < 600) return WeatherCondition.RAIN;   // drizzle + rain
        if (id >= 600 && id < 700) return WeatherCondition.SNOW;
        return WeatherCondition.CLEAR;
    }
}