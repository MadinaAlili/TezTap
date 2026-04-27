package com.teztap.controller;

import com.teztap.dto.CourierDeliveryDto;
import com.teztap.service.CourierDeliveryService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/couriers")
@RequiredArgsConstructor
public class CourierController {

    private final CourierDeliveryService courierDeliveryService;

    /**
     * GET /api/couriers/deliveries
     * Returns all deliveries ever assigned to the authenticated courier, newest first.
     * Used by the mobile app to populate the courier's delivery history screen.
     */
    @GetMapping("/deliveries")
    @PreAuthorize("hasRole('ROLE_COURIER')")
    public ResponseEntity<List<CourierDeliveryDto>> getDeliveryHistory(Authentication auth) {
        List<CourierDeliveryDto> history = courierDeliveryService.getDeliveryHistory(auth.getName());
        return ResponseEntity.ok(history);
    }

    /**
     * GET /api/couriers/deliveries/active
     * Returns the courier's current active (undelivered) delivery.
     * Returns 404 if no active delivery exists.
     * Used by the mobile app to populate the current delivery screen and show
     * the Finish Delivery button.
     */
    @GetMapping("/deliveries/active")
    @PreAuthorize("hasRole('ROLE_COURIER')")
    public ResponseEntity<CourierDeliveryDto> getActiveDelivery(Authentication auth) {
        try {
            CourierDeliveryDto active = courierDeliveryService.getActiveDelivery(auth.getName());
            return ResponseEntity.ok(active);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
