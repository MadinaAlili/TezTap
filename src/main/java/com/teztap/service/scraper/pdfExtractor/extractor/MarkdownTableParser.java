package com.teztap.service.scraper.pdfExtractor.extractor;

import com.teztap.service.scraper.pdfExtractor.model.ExtractedProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class MarkdownTableParser {

    private static final Logger log = LoggerFactory.getLogger(MarkdownTableParser.class);

    public List<ExtractedProduct> parse(String markdown) {
        List<ExtractedProduct> products = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return products;

        String lastCategory = null;

        for (String rawLine : markdown.split("\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("|")) continue;

            String lower = line.toLowerCase();
            if (lower.contains("məhsulun adı") || lower.contains("detallar")
                    || lower.contains("köhnə") || line.contains("---")) continue;

            // [0]="" [1]=name [2]=details [3]=oldPrice [4]=newPrice [5]=category [6]=""
            String[] cols = line.split("\\|", -1);
            if (cols.length < 6) continue;

            String name        = cols[1].trim();
            String details     = cols[2].trim();
            String oldPriceStr = cols[3].trim();
            String newPriceStr = cols[4].trim();
            String category    = cols[5].trim();

            if (name.isEmpty() || name.equals("-") || name.equalsIgnoreCase("N/A")) continue;
            if (!category.isEmpty()) lastCategory = category;

            BigDecimal oldPrice  = parsePriceSafe(oldPriceStr);
            BigDecimal newPrice  = parsePriceSafe(newPriceStr);
            Integer    discount  = calcDiscount(oldPrice, newPrice);

            products.add(ExtractedProduct.builder()
                    .name(name)
                    .details(details.isEmpty() ? null : details)
                    .oldPrice(oldPrice)
                    .newPrice(newPrice)
                    .categoryName(category.isEmpty() ? lastCategory : category)
                    .discountPct(discount)
                    .build());
        }

        log.debug("[Parser] Parsed {} products", products.size());
        return products;
    }

    private BigDecimal parsePriceSafe(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("N/A")) return null;
        try {
            return new BigDecimal(raw.replaceAll("[^\\d.]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer calcDiscount(BigDecimal old, BigDecimal nw) {
        if (old == null || nw == null || old.compareTo(BigDecimal.ZERO) <= 0) return null;
        return (int) Math.round((old.doubleValue() - nw.doubleValue()) / old.doubleValue() * 100);
    }
}
