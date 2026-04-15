package com.teztap.controller;


import com.teztap.dto.PriceEstimate;
import com.teztap.dto.PriceRequest;
import com.teztap.model.PricingConfig;
import com.teztap.service.PricingConfigService;
import com.teztap.service.PricingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for pricing.
 *
 * Customer endpoints (authenticated):
 *   POST /api/pricing/estimate           → get fare estimate before placing order
 *
 * Admin endpoints (ROLE_ADMIN required):
 *   GET    /api/admin/pricing/configs          → list all configs
 *   GET    /api/admin/pricing/configs/{id}     → get one config
 *   POST   /api/admin/pricing/configs          → create new config (starts inactive)
 *   PUT    /api/admin/pricing/configs/{id}     → update config fields
 *   POST   /api/admin/pricing/configs/{id}/activate → make this the live config
 *   DELETE /api/admin/pricing/configs/{id}     → delete (non-active only)
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PricingController {

    private final PricingService pricingService;
    private final PricingConfigService configService;

    // ── Customer ─────────────────────────────────────────────────────────────

    /**
     * Returns a full price estimate for a market→customer delivery.
     * Call this when the customer is reviewing their cart, before order creation.
     */
    @PostMapping("/pricing/estimate")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PriceEstimate> estimate(@RequestBody PriceRequest request) {
        return ResponseEntity.ok(pricingService.estimate(request));
    }

    // ── Admin: read ──────────────────────────────────────────────────────────

    @GetMapping("/admin/pricing/configs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PricingConfig>> getAll() {
        return ResponseEntity.ok(configService.getAll());
    }

    @GetMapping("/admin/pricing/configs/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PricingConfig> getById(@PathVariable Long id) {
        return ResponseEntity.ok(configService.getById(id));
    }

    // ── Admin: write ─────────────────────────────────────────────────────────

    @PostMapping("/admin/pricing/configs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PricingConfig> create(@RequestBody PricingConfig config) {
        return ResponseEntity.ok(configService.create(config));
    }

    @PutMapping("/admin/pricing/configs/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PricingConfig> update(
            @PathVariable Long id,
            @RequestBody PricingConfig config
    ) {
        return ResponseEntity.ok(configService.update(id, config));
    }

    /**
     * Activates the given config, deactivating all others.
     * This is the "go live with new pricing" button for admins.
     */
    @PostMapping("/admin/pricing/configs/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> activate(@PathVariable Long id) {
        configService.activate(id);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/admin/pricing/configs/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        configService.delete(id);
        return ResponseEntity.noContent().build();
    }
}