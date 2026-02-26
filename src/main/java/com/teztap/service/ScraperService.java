package com.teztap.service;

import com.teztap.model.Product;
import com.teztap.repository.ProductRepository;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

@Service
public class ScraperService {

    private final ProductRepository productRepository;
    private static final String BASE_URL = "https://arazmarket.az";
    private static final String CATEGORY_URL = "https://www.arazmarket.az/az/categories/spirtli-ickiler-571";

    public ScraperService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Scheduled(fixedRate = 10 * 60 * 1000) // every hour
    public void scrapeProducts() {

        ChromeOptions options = new ChromeOptions();
        // Required for running in a container/server environment
        options.addArguments("--headless=new");
        options.addArguments("--no-sandbox");           // Critical on Linux
        options.addArguments("--disable-dev-shm-usage"); // Overcomes limited /dev/shm
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--remote-debugging-port=9222");
        options.addArguments("--disable-extensions");

        // Tell Selenium where Chrome is installed
        options.setBinary("/usr/bin/google-chrome");

        WebDriver driver = new ChromeDriver(options);

        try {
            driver.get(CATEGORY_URL);
            Thread.sleep(6000); // wait for products to load

            // Simulate scrolling to load lazy products
            long lastHeight = (long) ((JavascriptExecutor) driver).executeScript("return document.body.scrollHeight");
            while (true) {
                ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, document.body.scrollHeight);");
                Thread.sleep(2000); // wait for new products
                long newHeight = (long) ((JavascriptExecutor) driver).executeScript("return document.body.scrollHeight");
                if (newHeight == lastHeight) break;
                lastHeight = newHeight;
            }

            List<WebElement> products = driver.findElements(By.cssSelector("div[class^=products-card_card__]"));

            for (WebElement product : products) {
                try {
                    // NAME
                    String name = "";
                    try {
                        name = product.findElement(By.tagName("h2")).getText();
                    } catch (NoSuchElementException ignored) {}

                    // PRICE
                    String price = "";
                    try {
                        WebElement priceEl = product.findElement(By.cssSelector("div[class^=products-card_price__] span"));
                        price = priceEl.getText();
                    } catch (NoSuchElementException ignored) {}

                    // LINK
                    String link = "";
                    try {
                        link = product.findElement(By.tagName("a")).getAttribute("href");
                        if (!link.startsWith("http")) {
                            link = BASE_URL + link;
                        }
                    } catch (NoSuchElementException ignored) {}

                    // IMAGE
                    String imageUrl = "";
                    try {
                        List<WebElement> imgs = product.findElements(By.tagName("img"));
                        if (!imgs.isEmpty()) {
                            WebElement imgTag = imgs.get(imgs.size() - 1);
                            String rawSrc = imgTag.getAttribute("src");
                            if (rawSrc.contains("url=")) {
                                String encoded = rawSrc.split("url=")[1].split("&")[0];
                                imageUrl = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                            }
                        }
                    } catch (Exception ignored) {}

                    // Skip products with no name or link
                    if (name.isEmpty() || link.isEmpty()) continue;

                    // CHECK IF EXISTS
                    Optional<Product> existing = productRepository.findByLink(link);
                    if (existing.isPresent()) {
                        Product existingProduct = existing.get();
                        if (!existingProduct.getPrice().equals(price)) {
                            existingProduct.setPrice(price);
                            existingProduct.setImageUrl(imageUrl);
                            productRepository.save(existingProduct);
                        }
                    } else {
                        Product p = new Product();
                        p.setName(name);
                        p.setPrice(price);
                        p.setLink(link);
                        p.setImageUrl(imageUrl);
                        productRepository.save(p);
                    }

                } catch (Exception e) {
                    // Catch any unexpected error per product so the loop continues
                    e.printStackTrace();
                }
            }

            System.out.println("Selenium scraping finished: " + products.size() + " products processed.");

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            driver.quit();
        }
    }
}