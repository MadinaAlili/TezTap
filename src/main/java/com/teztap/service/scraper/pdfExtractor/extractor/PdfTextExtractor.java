package com.teztap.service.scraper.pdfExtractor.extractor;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Extracts text and renders pages from PDF files.
 *
 * Two paths:
 *  1. Text-based PDFs  → extractText()      (free, no Claude call needed)
 *  2. Scanned/image PDFs → renderPagesOptimized()  (72 dpi + grayscale + JPEG 75)
 *
 * Rendering optimizations explained:
 *  - 72 dpi   → 1 tile per A4 page  → ~78% fewer tokens vs 300 dpi
 *  - Grayscale → better JPEG compression → smaller payload
 *  - JPEG q=75 → smaller bytes, same tile count
 *
 * All methods are safe to call on corrupt/empty/null paths — they log and
 * return empty results rather than propagating exceptions.
 */
@Component
public class PdfTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    /** PDFs with less than this many chars on page 1 are treated as scanned. */
    private static final int MIN_MEANINGFUL_CHARS = 150;

    /** DPI for rendering — 72 keeps token count at 1 tile per A4 page. */
    private static final int RENDER_DPI = 72;

    /** JPEG quality: 0.75 = good readability, strong compression. */
    private static final float JPEG_QUALITY = 0.75f;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Extracts all text from a text-based PDF.
     * Returns empty string (never null) if the PDF is image-based or extraction fails.
     */
    public String extractText(Path pdfPath) {
        if (pdfPath == null) return "";
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            if (text == null || text.strip().length() < MIN_MEANINGFUL_CHARS) {
                log.debug("[PdfText] Insufficient text ({} chars) — likely scanned",
                        text == null ? 0 : text.strip().length());
                return "";
            }
            log.debug("[PdfText] Extracted {} chars from {}", text.length(), pdfPath.getFileName());
            return text;
        } catch (Exception e) {
            log.warn("[PdfText] Text extraction failed for {}: {}", safeFileName(pdfPath), e.getMessage());
            return "";
        }
    }

    /**
     * Extracts text from page 1 only — used for cheap discount-catalogue classification.
     * Returns empty string on any failure.
     */
    public String extractPage1Text(Path pdfPath) {
        if (pdfPath == null) return "";
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String text = stripper.getText(doc);
            return text != null ? text : "";
        } catch (Exception e) {
            log.warn("[PdfText] Page-1 extraction failed for {}: {}", safeFileName(pdfPath), e.getMessage());
            return "";
        }
    }

    /**
     * Renders all pages at 72 dpi, converts to grayscale, compresses as JPEG.
     * Returns an empty list (never null) on any failure — caller must handle empty list.
     *
     * Use for scanned/image PDFs only. For text-based PDFs use extractText() instead.
     */
    public List<byte[]> renderPagesOptimized(Path pdfPath) {
        if (pdfPath == null) return Collections.emptyList();
        List<byte[]> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            if (doc.getNumberOfPages() == 0) {
                log.warn("[PdfText] PDF has 0 pages: {}", safeFileName(pdfPath));
                return Collections.emptyList();
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                try {
                    BufferedImage color = renderer.renderImageWithDPI(i, RENDER_DPI, ImageType.RGB);
                    BufferedImage gray  = toGrayscale(color);
                    byte[]        jpeg  = compressJpeg(gray, JPEG_QUALITY);
                    pages.add(jpeg);
                } catch (Exception e) {
                    // Skip the bad page, continue with the rest
                    log.warn("[PdfText] Failed to render page {} of {}: {}",
                            i + 1, safeFileName(pdfPath), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[PdfText] renderPagesOptimized failed for {}: {}", safeFileName(pdfPath), e.getMessage());
            return Collections.emptyList();
        }
        log.debug("[PdfText] Rendered {}/{} pages from {}",
                pages.size(), pages.size(), safeFileName(pdfPath));
        return pages;
    }

    /**
     * Renders page 1 only at 72 dpi → grayscale → JPEG.
     * Returns null on failure — callers must null-check.
     */
    public byte[] renderPage1Optimized(Path pdfPath) {
        if (pdfPath == null) return null;
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            if (doc.getNumberOfPages() == 0) return null;
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage color = renderer.renderImageWithDPI(0, RENDER_DPI, ImageType.RGB);
            BufferedImage gray  = toGrayscale(color);
            return compressJpeg(gray, JPEG_QUALITY);
        } catch (Exception e) {
            log.warn("[PdfText] Page-1 render failed for {}: {}", safeFileName(pdfPath), e.getMessage());
            return null;
        }
    }

    // ── Image processing helpers ──────────────────────────────────────────────

    /**
     * Converts any BufferedImage to TYPE_BYTE_GRAY.
     * Grayscale compresses much better as JPEG — ~15–25% fewer bytes.
     */
    private BufferedImage toGrayscale(BufferedImage src) {
        BufferedImage gray = new BufferedImage(
                src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return gray;
    }

    /**
     * Compresses a BufferedImage to JPEG at the given quality (0.0–1.0).
     * Uses explicit ImageWriteParam so quality is guaranteed to be respected.
     */
    private byte[] compressJpeg(BufferedImage img, float quality) throws IOException {
        // Convert to RGB first — JPEG encoder doesn't support TYPE_BYTE_GRAY directly
        // in all JVM implementations; converting is safer.
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ImageOutputStream ios    = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
            ios.flush();
            return bos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private String safeFileName(Path path) {
        try { return path.getFileName().toString(); } catch (Exception e) { return "<unknown>"; }
    }
}
