package com.teztap.service.scraper.pdfExtractor.service;

import com.teztap.service.scraper.pdfExtractor.model.ProcessedCatalogue;
import com.teztap.service.scraper.pdfExtractor.repository.ProcessedCatalogueRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcessedCatalogueService {

    private static final Logger log = LoggerFactory.getLogger(ProcessedCatalogueService.class);

    private final ProcessedCatalogueRepository repository;

    public boolean isProcessed(String url) {
        return repository.existsByUrl(url);
    }

    @Transactional
    public void markProcessed(String url, String marketName) {
        if (repository.existsByUrl(url)) return;
        try {
            repository.save(new ProcessedCatalogue(url, marketName));
        } catch (DataIntegrityViolationException e) {
            // Another thread saved it concurrently — safe to ignore
            log.debug("[ProcessedCatalogue] Concurrent save for: {}", url);
        }
    }
}
