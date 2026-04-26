package com.teztap.service;

import com.teztap.dto.AddressDto;
import com.teztap.dto.MarketBranchDto;
import com.teztap.dto.MarketDto;
import com.teztap.dto.MarketWithBranchesDto;
import com.teztap.model.Market;
import com.teztap.model.MarketBranch;
import com.teztap.repository.MarketBranchRepository;
import com.teztap.repository.MarketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketService {

    private final MarketRepository marketRepository;
    private final MarketBranchRepository marketBranchRepository;

    @Transactional(readOnly = true)
    public List<MarketDto> getAllMarkets() {
        log.info("Fetching all active markets");
        return marketRepository.findAll().stream()
                .map(this::mapToMarketDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public MarketDto getMarketById(Long id) {
        log.info("Fetching market with ID: {}", id);
        return marketRepository.findById(id)
                .map(this::mapToMarketDto)
                .orElseThrow(() -> {
                    log.error("Market not found with ID: {}", id);
                    return new ResourceNotFoundException("Market not found with ID: " + id);
                });
    }

    @Transactional(readOnly = true)
    public List<MarketBranchDto> getBranchesByMarketId(Long marketId) {
        log.info("Fetching branches for market ID: {}", marketId);

        // Fail-fast validation: Ensure the market actually exists before querying branches
        if (!marketRepository.existsById(marketId)) {
            log.error("Failed to fetch branches. Market not found with ID: {}", marketId);
            throw new ResourceNotFoundException("Market not found with ID: " + marketId);
        }

        return marketBranchRepository.findByMarketId(marketId).stream()
                .map(this::mapToBranchDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MarketBranchDto> getBranchesByMarketName(String marketName, int page, int size) {
        log.info("Fetching branches for market name: {} (Page: {}, Size: {})", marketName, page, size);

        // Convert 1-based user input to 0-based Spring Data pagination
        int pageNumber = page > 0 ? page - 1 : 0;
        Pageable pageable = PageRequest.of(pageNumber, size);

        Page<MarketBranch> branchPage = marketBranchRepository.findByMarket_NameIgnoreCase(marketName, pageable);

        // Optional fail-fast: if the market exists but has no branches vs market doesn't exist at all.
        // If returning an empty list is fine for unknown markets, you can skip existence checking here.

        return branchPage.getContent().stream()
                .map(this::mapToBranchDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public int getTotalPagesForMarketBranches(String marketName, int size) {
        log.info("Calculating total pages for market name: {} with page size: {}", marketName, size);

        // Avoid division by zero
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be greater than zero");
        }

        long totalElements = marketBranchRepository.countByMarket_NameIgnoreCase(marketName);

        // Calculate total pages: Math.ceil((double) totalElements / size)
        return (int) Math.ceil((double) totalElements / size);
    }

    @Transactional(readOnly = true)
    public List<MarketWithBranchesDto> getAllMarketsWithBranches() {
        log.info("Fetching all markets alongside their branches");
        // Utilizes the optimized @Query("... JOIN FETCH ...") from the previous step
        return marketRepository.findAllWithBranches().stream()
                .map(this::mapToMarketWithBranchesDto)
                .toList();
    }

    // ===================================================================================
    // PRIVATE MAPPING HELPER METHODS
    // Note: In a large-scale production app, replace these with a library like MapStruct.
    // However, explicit mapping is perfectly fine, highly performant, and easy to debug.
    // ===================================================================================

    private MarketDto mapToMarketDto(Market market) {
        return new MarketDto(
                market.getId(),
                market.getName(),
                market.getBaseUrl(),
                market.getLogoUrl(),
                market.getDisplayName(),
                market.getCategoryScrapingBaseUrl(),
                market.getActive(),
                market.getCreated()
        );
    }

    private MarketBranchDto mapToBranchDto(MarketBranch branch) {
        // 1. Safely map the Address to AddressDto
        AddressDto addressDto = null;
        if (branch.getAddress() != null) {
            Double lat = null;
            Double lng = null;

            // JTS Point: Y is Latitude, X is Longitude
            if (branch.getAddress().getLocation() != null) {
                lat = branch.getAddress().getLocation().getY();
                lng = branch.getAddress().getLocation().getX();
            }

            addressDto = new AddressDto(
                    lat,
                    lng,
                    branch.getAddress().getFullAddress(),
                    branch.getAddress().getAdditionalInfo()
            );
        }

        // 2. Return the Branch DTO
        return new MarketBranchDto(
                branch.getId(),
                branch.getName(),
                branch.getGooglePlaceId(),
                branch.getDescription(),
                addressDto, // <--- Pass the new DTO here
                branch.getPhoneNumber(),
                branch.getOpeningHours(),
                branch.isOpen24_7(),
                branch.getPlusCode(),
                branch.isActive()
        );
    }

    private MarketWithBranchesDto mapToMarketWithBranchesDto(Market market) {
        List<MarketBranchDto> branchDtos = marketBranchRepository.findByMarketId(market.getId()).stream()
                .map(this::mapToBranchDto)
                .toList();

        return new MarketWithBranchesDto(
                market.getId(),
                market.getName(),
                market.getBaseUrl(),
                market.getCategoryScrapingBaseUrl(),
                market.getActive(),
                market.getCreated(),
                branchDtos
        );
    }
}
