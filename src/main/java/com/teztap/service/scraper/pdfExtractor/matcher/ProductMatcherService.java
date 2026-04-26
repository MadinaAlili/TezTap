package com.teztap.service.scraper.pdfExtractor.matcher;

import com.teztap.model.Product;
import com.teztap.repository.ProductRepository;
import com.teztap.service.scraper.pdfExtractor.model.ExtractedProduct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Fuzzy-matches scraped product names against existing DB products.
 *
 * Match score >= 70 → transfer image_url from matched DB product.
 * Match score <  70 → product saved with image_url = null.
 *
 * DB load is paginated (500 per page) to avoid loading the entire
 * products table into heap memory at once.
 */
@Service
@RequiredArgsConstructor
public class ProductMatcherService {

    private static final Logger log               = LoggerFactory.getLogger(ProductMatcherService.class);
    private static final int    MIN_SCORE         = 70;
    private static final int    DB_PAGE_SIZE      = 100;

    private final ProductRepository productRepository;

    public void matchAll(List<ExtractedProduct> products) {
        if (products == null || products.isEmpty()) return;

        log.info("[Matcher] Loading DB products in pages of {}...", DB_PAGE_SIZE);

        // Build a lightweight name→imageUrl map page by page — never holds all
        // full Product entities in memory at the same time.
        Map<String, String> nameToImageUrl = new HashMap<>();
        int page = 0;
        Slice<Product> slice;
        do {
            slice = productRepository.findAll(PageRequest.of(page++, DB_PAGE_SIZE));
            for (Product p : slice) {
                if (p.getName() != null) nameToImageUrl.put(p.getName(), p.getImageUrl());
            }
        } while (slice.hasNext());

        log.info("[Matcher] Indexed {} DB product names", nameToImageUrl.size());

        int matched = 0, below = 0, none = 0;

        for (ExtractedProduct ep : products) {
            MatchResult result = findBestMatch(ep.getName(), ep.getDetails(), nameToImageUrl);
            if (result == null) {
                none++;
                continue;
            }
            ep.setMatchScore(result.score);
            if (result.score >= MIN_SCORE) {
                ep.setMatchedImageUrl(result.imageUrl);
                matched++;
            } else {
                below++;
            }
        }

        log.info("[Matcher] score≥{}: {}, below: {}, no match: {}", MIN_SCORE, matched, below, none);
    }

    // ── Matching ──────────────────────────────────────────────────────────────

    private MatchResult findBestMatch(String name, String details,
                                      Map<String, String> nameToImageUrl) {
        if (name == null || name.isBlank()) return null;

        String combined      = name.trim() + " " + (details != null ? details.trim() : "");
        String queryNorm     = normalize(combined);
        String detailsNorm   = normalize(details != null ? details : "");
        List<String> qTokens = tokenize(queryNorm);

        String bestName  = null;
        double bestScore = 0.40;

        for (String dbName : nameToImageUrl.keySet()) {
            double s = score(queryNorm, qTokens, detailsNorm, normalize(dbName));
            if (s > bestScore) { bestScore = s; bestName = dbName; }
        }

        if (bestName == null) return null;
        return new MatchResult((int) Math.round(bestScore * 100), nameToImageUrl.get(bestName));
    }

    private double score(String qNorm, List<String> qTokens, String detailsNorm, String dbNorm) {
        List<String> dbTokens = tokenize(dbNorm);
        if (qTokens.isEmpty() || dbTokens.isEmpty()) return 0.0;

        Set<String> qSet         = new HashSet<>(qTokens);
        Set<String> dSet         = new HashSet<>(dbTokens);
        Set<String> intersection = new HashSet<>(qSet); intersection.retainAll(dSet);
        Set<String> union        = new HashSet<>(qSet); union.addAll(dSet);

        double jaccard  = (double) intersection.size() / union.size();
        double coverage = (double) intersection.size() / qTokens.size();
        if (coverage < 0.5 && qTokens.size() >= 2) jaccard *= 0.4;

        double brandBonus = (!qTokens.isEmpty() && !dbTokens.isEmpty()
                && qTokens.get(0).equals(dbTokens.get(0))) ? 0.10 : 0.0;

        double productBonus = 0.0;
        if (qTokens.size() >= 2 && dbTokens.size() >= 2) {
            String qLast = qTokens.get(qTokens.size() - 1);
            String dLast = dbTokens.get(dbTokens.size() - 1);
            if (qLast.equals(dLast)) productBonus = 0.15;
            else if (qLast.length() > 3 && dLast.contains(qLast.substring(0, 4))) productBonus = 0.07;
        }

        double detailsBonus = (!detailsNorm.isBlank()
                && dbNorm.contains(detailsNorm.replaceAll("\\s", ""))) ? 0.10 : 0.0;

        return Math.min(jaccard + brandBonus + productBonus + detailsBonus, 1.0);
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replace("ə","e").replace("ı","i").replace("ö","o").replace("ü","u")
                .replace("ç","c").replace("ş","s").replace("ğ","g")
                .replace("İ","i").replace("Ə","e")
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private List<String> tokenize(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> t = new ArrayList<>();
        for (String p : s.split(" ")) if (!p.isBlank() && p.length() > 1) t.add(p);
        return t;
    }

    private record MatchResult(int score, String imageUrl) {}
}
