package com.teztap.dto;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.teztap.service.GeometryUtils;
import org.locationtech.jts.geom.Point;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@class")
public record MatchingState(
        Long deliveryId,
        List<String> offeredCouriers,
        int currentIndex,
        @JsonSerialize(using = GeometryUtils.LatLngSerializer.class)
        @JsonDeserialize(using = GeometryUtils.LatLngDeserializer.class)
        Point marketLocation
) {}