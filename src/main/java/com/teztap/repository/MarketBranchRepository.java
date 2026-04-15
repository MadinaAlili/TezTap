package com.teztap.repository;

import com.teztap.model.MarketBranch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MarketBranchRepository extends JpaRepository<MarketBranch, Long> {
    boolean existsByGooglePlaceId(String placeId);

    // Finds all branches associated with a specific market ID
    List<MarketBranch> findByMarketId(Long marketId);

    // Fetch paginated branches by market name (case insensitive)
    Page<MarketBranch> findByMarket_NameIgnoreCase(String marketName, Pageable pageable);

    //Count total branches for a specific market name to calculate pages
    long countByMarket_NameIgnoreCase(String marketName);
}
