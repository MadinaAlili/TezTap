package com.teztap.dto;

public record AddressDto(
    Double longitude,
    Double latitude,
    String fullAddress,
    String additionalInfo
){}