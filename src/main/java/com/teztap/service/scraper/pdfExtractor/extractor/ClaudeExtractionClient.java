package com.teztap.service.scraper.pdfExtractor.extractor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Handles all Claude API calls for catalogue extraction.
 *
 * Three extraction paths (cheapest → most expensive):
 *
 *   1. extractFromText()   → Haiku + plain text  (~90% cheaper than Sonnet+PDF)
 *   2. extractFromImages() → Haiku + JPEG images (for scanned PDFs)
 *   3. checkIsDiscountCatalogueVision() → Haiku + single image (last-resort classifier)
 *
 * The old extractFromPdf() (Sonnet + full PDF binary) is retained for
 * compatibility but should only be called if both text and image paths fail.
 *
 * Rate-limit strategy:
 *   - Text chunks: 3 s apart (tiny payloads, minimal token pressure)
 *   - Image pages: 10 s apart (heavier payloads)
 *   - On 429: exponential back-off up to MAX_RETRIES, respecting retry-after header
 *   - On 5xx: single retry after SERVER_ERROR_RETRY_MS
 *   - All public methods return empty string on unrecoverable failure (never throw to caller)
 */
@Component
public class ClaudeExtractionClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeExtractionClient.class);

    // ── Endpoints & models ────────────────────────────────────────────────────
    private static final String API_URL      = "https://api.anthropic.com/v1/messages";
    private static final String MODEL_HAIKU  = "claude-haiku-4-5-20251001";
    private static final String MODEL_SONNET = "claude-sonnet-4-6";

    // ── Token limits ──────────────────────────────────────────────────────────
    private static final int MAX_TOKENS          = 4096;
    private static final int CLASSIFY_MAX_TOKENS = 5;

    // ── Legacy PDF pagination (kept for fallback) ─────────────────────────────
    private static final int PAGES_PER_CALL  = 4;
    private static final int TOTAL_PDF_PAGES = 32;

    // ── Delay & retry config ──────────────────────────────────────────────────
    private static final int TEXT_CHUNK_DELAY_MS    =  3_000;
    private static final int IMAGE_CHUNK_DELAY_MS   = 10_000;
    private static final int PDF_CHUNK_DELAY_MS     = 35_000; // legacy, keeps free-tier safe
    private static final int RATE_LIMIT_RETRY_MS    = 65_000;
    private static final int SERVER_ERROR_RETRY_MS  = 10_000;
    private static final int MAX_RETRIES            = 3;

    // ── Text chunking ─────────────────────────────────────────────────────────
    private static final int WORDS_PER_TEXT_CHUNK = 3_000;

    // ── Prompts ───────────────────────────────────────────────────────────────
    private static final String EXTRACTION_SYSTEM_PROMPT =
            "Role: Senior Data Entry & OCR Specialist.\n" +
            "Task: Extract every discounted product from the attached catalogue content.\n" +
            "Output: Markdown table rows ONLY — no preamble, no headers, no commentary.\n\n" +
            "Schema: | Məhsulun Adı | Detallar/Miqdar | Köhnə Qiymət (AZN) | Yeni Qiymət (AZN) | Kateqoriya |\n\n" +
            "Rules:\n" +
            "1. Scan left-to-right, row-by-row. Every item with a visible price must appear.\n" +
            "2. List each distinct flavor/size/type as a separate row.\n" +
            "3. Keep brand and product names exactly as printed.\n" +
            "4. Category = Azerbaijani page-header text (e.g. Süd məhsulları, Şirniyyat).\n" +
            "5. Use N/A for any price that is blurry or missing.\n" +
            "6. Output table rows only — no header row, no separator row, no summaries.";

    @Value("${anthropic.api-key}")
    private String apiKey;

    private final HttpClient   http;
    private final ObjectMapper mapper;

    public ClaudeExtractionClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * PATH 1 — Cheapest. Use when PDFBox extracted meaningful text.
     *
     * Splits text into ~3 000-word chunks, sends each to Haiku as plain text.
     * No PDF binary upload → ~90% cheaper than the old Sonnet+PDF approach.
     *
     * Returns empty string on total failure (never throws).
     */
    public String extractFromText(String pdfText) {
        if (pdfText == null || pdfText.isBlank()) return "";

        List<String> chunks = splitIntoWordChunks(pdfText, WORDS_PER_TEXT_CHUNK);
        log.info("[Claude] Text mode (Haiku): {} chunk(s)", chunks.size());

        StringBuilder allRows = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) sleepSilently(TEXT_CHUNK_DELAY_MS);
            log.info("[Claude] Text chunk {}/{}", i + 1, chunks.size());
            try {
                String payload = buildTextPayload(chunks.get(i));
                allRows.append(callWithRetry(payload)).append("\n");
            } catch (Exception e) {
                log.error("[Claude] Text chunk {} failed: {}", i + 1, e.getMessage());
                // Continue with remaining chunks — partial extraction is better than none
            }
        }
        return allRows.toString();
    }

    /**
     * PATH 2 — Medium cost. Use when PDF is image/scanned.
     *
     * Sends each page image to Haiku separately with a 10 s delay between calls.
     * Images should be pre-processed (72 dpi, grayscale, JPEG 75) by PdfTextExtractor.
     *
     * Returns empty string on total failure (never throws).
     */
    public String extractFromImages(List<byte[]> pageImages) {
        if (pageImages == null || pageImages.isEmpty()) return "";

        log.info("[Claude] Vision mode (Haiku): {} page(s)", pageImages.size());
        StringBuilder allRows = new StringBuilder();

        for (int i = 0; i < pageImages.size(); i++) {
            if (i > 0) sleepSilently(IMAGE_CHUNK_DELAY_MS);
            log.info("[Claude] Vision page {}/{}", i + 1, pageImages.size());
            try {
                String payload = buildVisionPayload(pageImages.get(i), MODEL_HAIKU);
                allRows.append(callWithRetry(payload)).append("\n");
            } catch (Exception e) {
                log.error("[Claude] Vision page {} failed: {}", i + 1, e.getMessage());
                // Continue — don't abort remaining pages because one failed
            }
        }
        return allRows.toString();
    }

    /**
     * Kept for ALMarket compatibility — single image, Haiku.
     * Returns empty string on failure.
     */
    public String extractFromImage(byte[] imageBytes, String mediaType) {
        if (imageBytes == null || imageBytes.length == 0) return "";
        try {
            String payload = buildVisionPayloadWithMediaType(imageBytes, mediaType, MODEL_HAIKU);
            return callWithRetry(payload);
        } catch (Exception e) {
            log.error("[Claude] Single-image extraction failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Classifies page 1 as discount catalogue or not.
     *
     * PREFERRED: use TamStoreScraper's keyword check first (free).
     * Call this only when page-1 text extraction returned blank (truly scanned page).
     *
     * Returns true on API errors to avoid silently skipping valid catalogues.
     * Callers should treat "uncertain" as "probably process it".
     */
    public boolean checkIsDiscountCatalogueVision(byte[] page1Image) {
        if (page1Image == null || page1Image.length == 0) {
            log.warn("[Claude] classify: null/empty image — defaulting to true");
            return true;
        }
        try {
            String base64 = Base64.getEncoder().encodeToString(page1Image);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model",      MODEL_HAIKU);
            body.put("max_tokens", CLASSIFY_MAX_TOKENS);
            body.put("messages", List.of(Map.of("role", "user", "content", List.of(
                    imageBlock(base64, "image/jpeg"),
                    Map.of("type", "text", "text",
                            "Does this catalogue page show discounted/promotional grocery prices? Reply YES or NO only.")
            ))));

            String answer = callWithRetry(mapper.writeValueAsString(body));
            boolean isDiscount = answer != null && answer.toUpperCase().contains("YES");
            log.info("[Claude] classify result: '{}' → {}", answer, isDiscount);
            return isDiscount;

        } catch (Exception e) {
            log.error("[Claude] Catalogue classification failed: {} — defaulting to true", e.getMessage());
            return true; // fail-open: process it rather than silently skip
        }
    }

    /**
     * LEGACY PATH — Sonnet + full PDF binary upload.
     *
     * Kept for backward compatibility. Only use this if both text and image paths
     * are genuinely unavailable. It is significantly more expensive.
     *
     * Returns empty string on failure.
     */
    public String extractFromPdf(java.nio.file.Path pdfPath) {
        if (pdfPath == null) return "";
        try {
            log.info("[Claude] LEGACY PDF mode (Sonnet): {}", pdfPath.getFileName());
            byte[] pdfBytes  = java.nio.file.Files.readAllBytes(pdfPath);
            String base64Pdf = Base64.getEncoder().encodeToString(pdfBytes);
            pdfBytes = null; // allow GC before API calls

            int totalCalls = (int) Math.ceil((double) TOTAL_PDF_PAGES / PAGES_PER_CALL);
            StringBuilder allRows = new StringBuilder();

            for (int callNum = 1, start = 1; start <= TOTAL_PDF_PAGES; start += PAGES_PER_CALL, callNum++) {
                int end = Math.min(start + PAGES_PER_CALL - 1, TOTAL_PDF_PAGES);
                if (callNum > 1) sleepSilently(PDF_CHUNK_DELAY_MS);

                log.info("[Claude] Legacy call {}/{} — pages {}-{}", callNum, totalCalls, start, end);
                try {
                    String userMsg = "Extract ALL products from pages " + start + " to " + end
                            + ". Output ONLY Markdown table rows.";
                    allRows.append(callWithRetry(buildLegacyPdfPayload(base64Pdf, userMsg))).append("\n");
                } catch (Exception e) {
                    log.error("[Claude] Legacy PDF call {}/{} failed: {}", callNum, totalCalls, e.getMessage());
                }
            }
            return allRows.toString();

        } catch (Exception e) {
            log.error("[Claude] Legacy PDF extraction failed for {}: {}", pdfPath.getFileName(), e.getMessage());
            return "";
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INTERNAL — Retry wrapper
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Wraps a single API call with exponential back-off on 429 and one retry on 5xx.
     * Throws IOException after MAX_RETRIES — callers decide how to handle.
     */
    private String callWithRetry(String payload) throws IOException, InterruptedException {
        int waitMs = RATE_LIMIT_RETRY_MS;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return callApi(payload);

            } catch (RateLimitException e) {
                if (attempt == MAX_RETRIES)
                    throw new IOException("Rate limit exceeded after " + MAX_RETRIES + " retries");

                int retryAfterMs = e.retryAfterSeconds > 0
                        ? (e.retryAfterSeconds + 2) * 1_000
                        : waitMs;
                log.warn("[Claude] Rate limited. Waiting {}s (attempt {}/{})...",
                        retryAfterMs / 1_000, attempt, MAX_RETRIES);
                sleepSilently(retryAfterMs);
                waitMs *= 2;

            } catch (ServerErrorException e) {
                if (attempt == MAX_RETRIES)
                    throw new IOException("Server error after retries: " + e.getMessage());

                log.warn("[Claude] Server error {}. Retrying in {}s...",
                        e.getMessage(), SERVER_ERROR_RETRY_MS / 1_000);
                sleepSilently(SERVER_ERROR_RETRY_MS);
            }
        }
        return ""; // unreachable — loop always throws or returns, but compiler needs it
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INTERNAL — Raw API call
    // ═════════════════════════════════════════════════════════════════════════

    private String callApi(String payload)
            throws IOException, InterruptedException, RateLimitException, ServerErrorException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type",    "application/json")
                .header("x-api-key",       apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofMinutes(3))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        if (status == 429) {
            int retryAfter = 0;
            try {
                retryAfter = Integer.parseInt(
                        response.headers().firstValue("retry-after").orElse("0"));
            } catch (Exception ignored) {}
            throw new RateLimitException(retryAfter);
        }
        if (status >= 500) throw new ServerErrorException(String.valueOf(status));
        if (status != 200) throw new IOException("Claude API " + status + ": " + response.body());

        try {
            JsonNode content = mapper.readTree(response.body()).path("content");
            if (content.isArray() && !content.isEmpty()) {
                return content.get(0).path("text").asText("");
            }
        } catch (Exception e) {
            log.error("[Claude] Failed to parse API response: {}", e.getMessage());
        }
        return "";
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INTERNAL — Payload builders
    // ═════════════════════════════════════════════════════════════════════════

    /** PATH 1: plain text → Haiku */
    private String buildTextPayload(String chunk) throws IOException {
        String userMsg = "Extract ALL discounted products from this catalogue text.\n"
                       + "Output ONLY markdown table rows.\n\n" + chunk;
        Map<String, Object> body = baseBody(MODEL_HAIKU, MAX_TOKENS);
        body.put("system",   EXTRACTION_SYSTEM_PROMPT);
        body.put("messages", List.of(Map.of("role", "user", "content", userMsg)));
        return mapper.writeValueAsString(body);
    }

    /** PATH 2: single JPEG image → configurable model */
    private String buildVisionPayload(byte[] imageBytes, String model) throws IOException {
        return buildVisionPayloadWithMediaType(imageBytes, "image/jpeg", model);
    }

    private String buildVisionPayloadWithMediaType(byte[] imageBytes, String mediaType, String model)
            throws IOException {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        Map<String, Object> body = baseBody(model, MAX_TOKENS);
        body.put("system",   EXTRACTION_SYSTEM_PROMPT);
        body.put("messages", List.of(Map.of("role", "user", "content", List.of(
                imageBlock(base64, mediaType),
                Map.of("type", "text", "text",
                        "Extract all discounted products. Output ONLY markdown table rows.")
        ))));
        return mapper.writeValueAsString(body);
    }

    /** LEGACY: full PDF binary → Sonnet */
    private String buildLegacyPdfPayload(String base64Pdf, String userMessage) throws IOException {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type",       "base64");
        source.put("media_type", "application/pdf");
        source.put("data",       base64Pdf);

        Map<String, Object> docBlock = new LinkedHashMap<>();
        docBlock.put("type",   "document");
        docBlock.put("source", source);

        Map<String, Object> body = baseBody(MODEL_SONNET, MAX_TOKENS);
        body.put("system",   EXTRACTION_SYSTEM_PROMPT);
        body.put("messages", List.of(Map.of("role", "user", "content", List.of(
                docBlock,
                Map.of("type", "text", "text", userMessage)
        ))));
        return mapper.writeValueAsString(body);
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private Map<String, Object> baseBody(String model, int maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model",      model);
        body.put("max_tokens", maxTokens);
        return body;
    }

    private Map<String, Object> imageBlock(String base64, String mediaType) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type",       "base64");
        source.put("media_type", mediaType);
        source.put("data",       base64);

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type",   "image");
        block.put("source", source);
        return block;
    }

    private List<String> splitIntoWordChunks(String text, int wordsPerChunk) {
        if (text == null || text.isBlank()) return List.of();
        String[] words = text.split("\\s+");
        List<String> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int count = 0;
        for (String w : words) {
            buf.append(w).append(' ');
            if (++count >= wordsPerChunk) {
                chunks.add(buf.toString().trim());
                buf = new StringBuilder();
                count = 0;
            }
        }
        if (!buf.isEmpty()) chunks.add(buf.toString().trim());
        return chunks;
    }

    /** Sleeps without propagating InterruptedException — logs a warning instead. */
    private void sleepSilently(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Claude] Sleep interrupted");
        }
    }

    // ── Internal exception types ──────────────────────────────────────────────

    private static class RateLimitException extends Exception {
        final int retryAfterSeconds;
        RateLimitException(int retryAfterSeconds) {
            super("429");
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private static class ServerErrorException extends Exception {
        ServerErrorException(String status) { super(status); }
    }
}
