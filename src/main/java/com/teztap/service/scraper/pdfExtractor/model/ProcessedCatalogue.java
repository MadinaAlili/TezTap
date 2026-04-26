package com.teztap.service.scraper.pdfExtractor.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "processed_catalogues", indexes = {
        @Index(name = "idx_processed_catalogues_url", columnList = "url", unique = true)
})
public class ProcessedCatalogue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 2000)
    private String url;

    @Column(nullable = false)
    private String marketName;

    @CreationTimestamp
    private LocalDateTime processedAt;

    public ProcessedCatalogue() {}

    public ProcessedCatalogue(String url, String marketName) {
        this.url        = url;
        this.marketName = marketName;
    }
}
