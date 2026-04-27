package com.teztap.dto;

import com.teztap.model.Payment;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class OrderRequest {
    @NotNull(message = "Branch ID is required")
    private List<Long> branchIds;

//    @NotEmpty(message = "Order must have at least one item")
//    private List<CartItemRequest> items;

    // The user might be ordering to a different location than their home
    @NotNull(message = "Delivery address is required")
    private AddressDto deliveryAddress;

    private String note; // e.g., "Don't ring the bell, baby is sleeping"

    private Payment.PaymentMethod paymentMethod; // e.g., CARD, WALLET
}

