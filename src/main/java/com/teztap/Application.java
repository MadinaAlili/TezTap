package com.teztap;

import com.teztap.service.ScraperService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

@SpringBootApplication
@EnableScheduling
public class Application {

    public static void main(String[] args) {
//        String pdfURL = "https://b7x9kq.arazmarket.az/storage/discount-journal/file/01KG25JQT51ZWQNYXQ2DYANXFP.pdf";
//        String path = "./araz.pdf";
//        String path2 = "./araz_splitted.pdf";
//        downloadPDF(pdfURL, path);
//        splitPDF(path, path2);
//
//        // get document ai results
//        try{
//            ProcessDocument.processDocument();
//        } catch (Exception e) {
//            e.printStackTrace();
//            System.err.println("Error downloading the file.");
//        }

        ApplicationContext context = SpringApplication.run(Application.class, args);

        // Get ScraperService bean
        ScraperService scraperService = context.getBean(ScraperService.class);

        // Call scrape method manually to test
        scraperService.scrapeProducts();

        System.out.println("Test scraping finished.");

    }

    public static String encodeFileToBase64String(File file) throws IOException {
        // Read all bytes from the file
        byte[] fileContent = Files.readAllBytes(file.toPath());

        // Encode the byte array to a Base64 string
        return Base64.getEncoder().encodeToString(fileContent);
    }

    private static void downloadPDF(String urlString, String destinationPath){
//        String destinationPath = "./araz.pdf";

        try {
            URL url = new URL(urlString);
            // Use try-with-resources to ensure the InputStream is closed automatically
            try (InputStream in = url.openStream()) {
                Files.copy(in, Paths.get(destinationPath), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("PDF downloaded successfully to: " + destinationPath);
            }
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("Error downloading the file.");
        }
    }

    public static void splitPDF(String inputFilePath, String outputFilePath) {
        int startPage = 1; // 1-based
        int endPage = 10;

        try (PDDocument source = Loader.loadPDF(new File(inputFilePath));
             PDDocument target = new PDDocument()) {

            int totalPages = source.getNumberOfPages();

            // Safety check
            if (startPage < 1 || endPage > totalPages || startPage > endPage) {
                throw new IllegalArgumentException("Invalid page range");
            }

            // PDFBox pages are 0-based internally
            for (int i = startPage - 1; i < endPage; i++) {
                target.addPage(source.getPage(i));
            }

            target.save(outputFilePath);
            System.out.println("Successfully extracted pages " + startPage + " to " + endPage);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
