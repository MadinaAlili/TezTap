package com.teztap.dto;

import java.util.List;

public record SubOrderDto(
        Long subOrderId,
        Long marketBranchId,
        String marketName,      // e.g., "McDonald's"
        AddressDto branchAddress,   // e.g., "Downtown Branch"
        String status,          // e.g., "ON_THE_WAY" (Specific to this branch's courier!)
        List<OrderItemDto> items // The items for just this branch
) {}
