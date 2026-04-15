package com.teztap.repository;


import com.teztap.model.PricingConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PricingConfigRepository extends JpaRepository<PricingConfig, Long> {

    /** Returns the single currently-active pricing configuration. */
    Optional<PricingConfig> findByActiveTrue();
}