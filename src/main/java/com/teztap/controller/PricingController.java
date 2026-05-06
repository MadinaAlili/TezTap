package com.teztap.controller;

import com.teztap.dto.PriceEstimate;
import com.teztap.dto.PriceRequest;
import com.teztap.model.MarketBranch;
import com.teztap.model.PricingConfig;
import com.teztap.repository.MarketBranchRepository;
import com.teztap.service.PricingConfigService;
import com.teztap.service.PricingService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * REST API for pricing.
 *
 * Customer endpoints (authenticated):
 *   POST /api/pricing/estimate
 *       → Full estimate from raw coordinates (market lat/lng + customer lat/lng)
 *
 *   GET  /api/pricing/estimate/branch/{branchId}?customerLat=&customerLng=
 *       → Convenience endpoint for checkout page: client passes the selected
 *         branch ID + the customer's pinned location. Backend reads the branch
 *         coordinates from the DB — client doesn't need to know them.
 *
 * Admin endpoints (ROLE_ADMIN required):
 *   GET    /api/admin/pricing/configs
 *   GET    /api/admin/pricing/configs/{id}
 *   POST   /api/admin/pricing/configs
 *   PUT    /api/admin/pricing/configs/{id}
 *   POST   /api/admin/pricing/configs/{id}/activate
 *   DELETE /api/admin/pricing/configs/{id}
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PricingController {

    private final PricingService pricingService;
    private final PricingConfigService configService;
    private final MarketBranchRepository marketBranchRepository;

    // ── Customer: raw estimate ────────────────────────────────────────────────

    /**
     * Full price estimate from raw coordinates.
     * Use this when you already have both sets of coordinates on the client.
     */
    @PostMapping("/pricing/estimate")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PriceEstimate> estimate(@RequestBody PriceRequest request) {
        return ResponseEntity.ok(pricingService.estimate(request));
    }

    // ── Customer: branch-aware checkout estimate ──────────────────────────────

    /**
     * Checkout page estimate. The client sends:
     *   - branchId  (the market branch they're ordering from)
     *   - customerLat / customerLng  (the pin the customer dropped on the map)
     *
     * The backend reads the branch coordinates from the DB so the client
     * never needs to store or pass branch coordinates.
     *
     * Called every time the customer moves their pin or changes branch.
     *
     * Example:
     *   GET /api/pricing/estimate/branch/7?customerLat=40.4150&customerLng=49.8750
     */
    @GetMapping("/pricing/estimate/branch/{branchId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PriceEstimate> estimateForBranch(
            @PathVariable Long branchId,
            @RequestParam BigDecimal customerLat,
            @RequestParam BigDecimal customerLng
    ) {
        MarketBranch branch = marketBranchRepository.findById(branchId)
                .orElseThrow(() -> new EntityNotFoundException("Branch not found: " + branchId));

        double branchLat = branch.getAddress().getLocation().getY();
        double branchLng = branch.getAddress().getLocation().getX();

        PriceRequest request = new PriceRequest(
                BigDecimal.valueOf(branchLat),
                BigDecimal.valueOf(branchLng),
                customerLat,
                customerLng
        );

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
