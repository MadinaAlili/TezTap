package com.teztap.controller;

import com.teztap.dto.MarketBranchDto;
import com.teztap.dto.MarketDto;
import com.teztap.dto.MarketWithBranchesDto;
import com.teztap.service.MarketService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/markets")
public class MarketController {

    private final MarketService marketService;

    public MarketController(MarketService marketService) {
        this.marketService = marketService;
    }

    // 1. Get all markets (without branches)
    @GetMapping
    public ResponseEntity<List<MarketDto>> getAllMarkets() {
        return ResponseEntity.ok(marketService.getAllMarkets());
    }

    // 2. Get a specific market by ID
    @GetMapping("/{id}")
    public ResponseEntity<MarketDto> getMarketById(@PathVariable Long id) {
        return ResponseEntity.ok(marketService.getMarketById(id));
    }

    // 3. Get all branches for a specific market
    @GetMapping("/{marketId}/branches")
    public ResponseEntity<List<MarketBranchDto>> getBranchesByMarketId(@PathVariable Long marketId) {
        return ResponseEntity.ok(marketService.getBranchesByMarketId(marketId));
    }

    // 4. Get all markets along with their nested branches in one payload
    @GetMapping("/with-branches")
    public ResponseEntity<List<MarketWithBranchesDto>> getAllMarketsWithBranches() {
        return ResponseEntity.ok(marketService.getAllMarketsWithBranches());
    }

    // 5. Get branches by market name with pagination
    @GetMapping("/market/{marketName}/branches")
    public ResponseEntity<List<MarketBranchDto>> getBranchesByMarketName(
            @PathVariable String marketName,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(marketService.getBranchesByMarketName(marketName, page, size));
    }

    // 6. Get total number of pages for a specific market's branches
    @GetMapping("/market/{marketName}/branches/page-count")
    public ResponseEntity<Integer> getBranchPageCount(
            @PathVariable String marketName,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(marketService.getTotalPagesForMarketBranches(marketName, size));
    }
}
