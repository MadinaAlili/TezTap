package com.teztap.service;


import com.teztap.model.PricingConfig;
import com.teztap.repository.PricingConfigRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin-facing service for managing pricing configurations.
 *
 * Rules enforced here:
 *  - Exactly one config can be active at a time.
 *  - New configs start inactive (admin must explicitly activate).
 *  - The active config cannot be deleted.
 *  - Activation deactivates all others atomically in one transaction.
 */
@Service
@RequiredArgsConstructor
public class PricingConfigService {

    private final PricingConfigRepository repository;

    // ── Read ─────────────────────────────────────────────────────────────────

    /** Used internally by PricingService on every estimate call. */
    public PricingConfig getActiveConfig() {
        return repository.findByActiveTrue()
                .orElseThrow(() -> new EntityNotFoundException(
                        "No active pricing config found. Activate one via POST /admin/pricing/configs/{id}/activate"));
    }

    public List<PricingConfig> getAll() {
        return repository.findAll();
    }

    public PricingConfig getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Pricing config not found: " + id));
    }

    // ── Write ────────────────────────────────────────────────────────────────

    /** Creates a new config in inactive state. Admin must activate it separately. */
    @Transactional
    public PricingConfig create(PricingConfig config) {
        config.setId(null);       // ensure it's a new entity
        config.setActive(false);  // never auto-activate on creation
        return repository.save(config);
    }

    /**
     * Updates all fields of an existing config.
     * Active status is preserved — you must use activate() to change it.
     */
    @Transactional
    public PricingConfig update(Long id, PricingConfig incoming) {
        PricingConfig existing = getById(id);
        incoming.setId(existing.getId());
        incoming.setActive(existing.isActive());   // preserve active flag
        return repository.save(incoming);
    }

    /**
     * Sets the given config as the only active config.
     * All others are deactivated atomically in the same transaction.
     */
    @Transactional
    public void activate(Long id) {
        // Deactivate all configs
        repository.findAll().forEach(c -> {
            if (c.isActive()) {
                c.setActive(false);
                repository.save(c);
            }
        });

        // Activate the target
        PricingConfig target = getById(id);
        target.setActive(true);
        repository.save(target);
    }

    /** Deletes a config. The active config is protected from deletion. */
    @Transactional
    public void delete(Long id) {
        PricingConfig config = getById(id);
        if (config.isActive()) {
            throw new IllegalStateException(
                    "Cannot delete the active pricing config. Activate another config first.");
        }
        repository.delete(config);
    }
}