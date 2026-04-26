package com.teztap.service.scraper.pdfExtractor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Raw product data extracted from a market catalogue PDF/image.
 * This is NOT a JPA entity — it's a pure DTO used during scraping.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedProduct {

    /** Product name as it appears in the catalogue (e.g. "GÜNAYDIM") */
    private String name;

    /** Extra detail string from catalogue (e.g. "Kərə yağı 82%, 1 kq") */
    private String details;

    private BigDecimal oldPrice;
    private BigDecimal newPrice;

    /** Category name as extracted from the catalogue page header */
    private String categoryName;

    /** Calculated discount %, filled automatically */
    private Integer discountPct;

    // ── Fields populated by ProductMatcherService after DB lookup ──────────

    /** image_url taken from the matched DB product (null if no match / low score) */
    private String matchedImageUrl;

    /** Fuzzy match score 0-100, null if no match attempted yet */
    private Integer matchScore;

    /** DB product id of the best match, null if no match */
    private Long matchedDbId;
}
