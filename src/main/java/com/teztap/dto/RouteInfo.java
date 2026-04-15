package com.teztap.dto;

/**
 * Result from OpenRouteService (or Haversine fallback).
 *
 * @param distanceKm       road distance in kilometres
 * @param durationMinutes  estimated travel time in minutes
 * @param encodedPolyline  ORS-encoded geometry string for map display (empty on fallback)
 */
public record RouteInfo(
        double distanceKm,
        double durationMinutes,
        String encodedPolyline
) {}