package com.teztap.dto;

import java.math.BigDecimal;

public record AddressRequest(BigDecimal lng, BigDecimal lat,
                             String fullAddress, String city, String district, String additionalInfo) {}
