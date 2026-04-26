package com.teztap.dto;

import com.teztap.model.Address;
import com.teztap.model.Order;

import java.util.Date;
import java.util.List;

public record OrderSummaryDto(
        Long orderId,
        String overallStatus,     // The parent order status (e.g., PAID, PENDING)
        Date createdAt,
        Date updatedAt,
        String deliveryNote,
        AddressDto deliveryAddress, // Using your new AddressDto!
        Long paymentId,
        List<SubOrderSummaryDto> deliveries // Replaces the single marketBranchId
) {}
