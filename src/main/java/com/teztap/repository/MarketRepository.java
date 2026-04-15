package com.teztap.repository;

import com.teztap.model.Market;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MarketRepository extends JpaRepository<Market, Long> {
    boolean existsByNameOrBaseUrl(String name, String baseUrl);

    Optional<Market> findByName(String name);

    // Optimization: Fetches Markets and Branches in a single SQL JOIN query
    @Query("SELECT m FROM Market m LEFT JOIN FETCH MarketBranch mb ON mb.market = m")
    List<Market> findAllWithBranches();

    // Alternatively, using EntityGraph if you added a @OneToMany relationship in the Market entity
    // @EntityGraph(attributePaths = {"branches"})
    // List<Market> findAll();
}
