package com.teztap.service.scraper.pdfExtractor.repository;

import com.teztap.service.scraper.pdfExtractor.model.ProcessedCatalogue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedCatalogueRepository extends JpaRepository<ProcessedCatalogue, Long> {
    boolean existsByUrl(String url);
}
