package com.teztap.service.scraper.araz;

import com.teztap.model.Category;
import com.teztap.model.Product;
import com.teztap.repository.CategoryRepository;
import com.teztap.repository.ProductRepository;
import com.teztap.service.scraper.Scraper;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@Transactional(propagation= Propagation.REQUIRED, noRollbackFor=Exception.class)
public class ArazProductScraper implements Scraper<List<Product>> {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private static final String BASE_URL = "https://arazmarket.az/az";

    public ArazProductScraper(ProductRepository productRepository, CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    @Override
    public List<Product> scrape(String categoryUrl) {
        Optional<Category> productCat = categoryRepository.findByUrl(categoryUrl);
        System.out.println("----------------" + productCat.get());
//        System.out.println();
        List<Product> scrapedProducts = new ArrayList<>();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=1920,1080");
//        options.addArguments("--remote-debugging-port=9222");
        options.addArguments("--disable-extensions");
//        options.setBinary("/usr/bin/google-chrome");

        WebDriver driver = new ChromeDriver(options);

        int page = 1;
        while (true) {

            String pagedUrl = page == 1
                    ? categoryUrl
                    : categoryUrl + "?page=" + page;
            System.out.println(pagedUrl);
            try {
                driver.get(pagedUrl);
                Thread.sleep(2000);

                // Scroll to load lazy products
                long lastHeight = (long) ((JavascriptExecutor) driver).executeScript("return document.body.scrollHeight");
                while (true) {
                    ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, document.body.scrollHeight);");
                    Thread.sleep(1000);
                    long newHeight = (long) ((JavascriptExecutor) driver).executeScript("return document.body.scrollHeight");
                    if (newHeight == lastHeight) break;
                    lastHeight = newHeight;
                }


                List<WebElement> products = driver.findElements(
                        By.cssSelector(".inner-content_inner__GXOwD div[class^='products-card_card__']")
                );

                if (products.isEmpty()) {
                    break; // no more pages
                }

                for (WebElement productEl : products) {
                    try {
                        String name = "";
                        BigDecimal originalPrice = BigDecimal.valueOf(0.0);
                        BigDecimal discountPrice = null;
                        BigDecimal discountPercentage = null;
                        String link = "";
                        String imageUrl = "";


                        // NAME
                        try {
                            name = productEl.findElement(By.tagName("h2")).getText();
                        } catch (NoSuchElementException ignored) {
                        }

                        // PRICE
                        try {
                            originalPrice = BigDecimal.valueOf(Double.parseDouble(productEl.findElement(By.cssSelector("div[class^=products-card_price__] span")).getText()));
                        } catch (NoSuchElementException ignored) {
                            // DISCOUNT PRICE
                            try {
                                discountPrice = BigDecimal.valueOf(Double.parseDouble(productEl.findElement(By.cssSelector("div[class^=products-card_price_discount__] span")).getText()));
                                originalPrice = BigDecimal.valueOf(Double.parseDouble(productEl.findElement(By.cssSelector("div[class^=products-card_price_discount__] del")).getText()));
                                discountPercentage = BigDecimal.valueOf(Double.parseDouble(productEl.findElement(By.cssSelector("div[class^=products-card_badge_option__] span:last-of-type")).getText().replaceAll("[^0-9]","")));
                            } catch (NoSuchElementException ignore) {
                            }
                        }

                        // LINK
                        try {
                            link = productEl.findElement(By.tagName("a")).getAttribute("href");
                            if (!link.startsWith("http")) link = BASE_URL + link;
                        } catch (NoSuchElementException ignored) {
                        }

                        // IMAGE
                        try {
                            List<WebElement> imgs = productEl.findElements(By.tagName("img"));
                            if (!imgs.isEmpty()) {
                                WebElement imgTag = imgs.get(imgs.size() - 1);
                                String rawSrc = imgTag.getAttribute("src");
                                if (rawSrc.contains("url=")) {
                                    String encoded = rawSrc.split("url=")[1].split("&")[0];
                                    imageUrl = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                                } else {
                                    imageUrl = rawSrc;
                                }
                            }
                        } catch (Exception ignored) {
                        }

                        if (name.isEmpty() || link.isEmpty()) continue;


                        // Save or update DB
                        Optional<Product> existing = productRepository.findByLink(link);
                        if (existing.isPresent()) {
                            Product p = existing.get();
                            if (!p.getOriginalPrice().equals(originalPrice)) {
                                p.setOriginalPrice(originalPrice);
                                p.setImageUrl(imageUrl);
                                p.setDiscountPrice(discountPrice);
                                p.setDiscountPercentage(discountPercentage);
                                p.setCategory(productCat.orElse(null));
//                                System.out.println("----------------" + productCat.orElse(null));
                                productRepository.save(p);
//                                System.out.println("product saved to db");
                            }
                            scrapedProducts.add(p);
                        } else {
                            Product p = new Product();
                            p.setName(name);
                            p.setOriginalPrice(originalPrice);
                            p.setDiscountPrice(discountPrice);
                            p.setDiscountPercentage(discountPercentage);
                            p.setLink(link);
                            p.setImageUrl(imageUrl);
                            p.setCategory(productCat.orElse(null));
                            productRepository.save(p);
                            scrapedProducts.add(p);
                        }

                    } catch (Exception e) {
                        e.printStackTrace(); // Log individual product errors
                    }
                }

            } catch (Exception e) {
                e.printStackTrace(); // Log overall scraping errors
                break;
            }
            page++;
        }
        driver.quit();
        return scrapedProducts;
    }

}

//@Service
//public class ArazProductScraper {
//
//    private final ProductRepository productRepository;
//    private static final String BASE_URL = "https://arazmarket.az";
//    private static final String CATEGORY_URL = "https://www.arazmarket.az/az/categories/spirtli-ickiler-571";
//
//    public ArazProductScraper(ProductRepository productRepository) {
//        this.productRepository = productRepository;
//    }
//
//    @Scheduled(fixedRate = 10 * 60 * 1000) // every hour
//    public void scrapeProducts() {
//
//        ChromeOptions options = new ChromeOptions();
//        // Required for running in a container/server environment
//        options.addArguments("--headless=new");
//        options.addArguments("--no-sandbox");           // Critical on Linux
//        options.addArguments("--disable-dev-shm-usage"); // Overcomes limited /dev/shm
//        options.addArguments("--disable-gpu");
//        options.addArguments("--window-size=1920,1080");
//        options.addArguments("--remote-debugging-port=9222");
//        options.addArguments("--disable-extensions");
//
//        // Tell Selenium where Chrome is installed
//        options.setBinary("/usr/bin/google-chrome");
//
//        WebDriver driver = new ChromeDriver(options);
//
//        try {
//            driver.get(CATEGORY_URL);
//            Thread.sleep(6000); // wait for products to load
//
//            // Simulate scrolling to load lazy products
//            long lastHeight = (long) ((JavascriptExecutor) driver).executeScript("return document.body.scrollHeight");
//            while (true) {
//                ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, document.body.scrollHeight);");
//                Thread.sleep(2000); // wait for new products
//                long newHeight = (long) ((JavascriptExecutor) driver).executeScript("return document.body.scrollHeight");
//                if (newHeight == lastHeight) break;
//                lastHeight = newHeight;
//            }
//
//            List<WebElement> products = driver.findElements(By.cssSelector("div[class^=products-card_card__]"));
//
//            for (WebElement product : products) {
//                try {
//                    // NAME
//                    String name = "";
//                    try {
//                        name = product.findElement(By.tagName("h2")).getText();
//                    } catch (NoSuchElementException ignored) {}
//
//                    // PRICE
//                    String price = "";
//                    try {
//                        WebElement priceEl = product.findElement(By.cssSelector("div[class^=products-card_price__] span"));
//                        price = priceEl.getText();
//                    } catch (NoSuchElementException ignored) {}
//
//                    // LINK
//                    String link = "";
//                    try {
//                        link = product.findElement(By.tagName("a")).getAttribute("href");
//                        if (!link.startsWith("http")) {
//                            link = BASE_URL + link;
//                        }
//                    } catch (NoSuchElementException ignored) {}
//
//                    // IMAGE
//                    String imageUrl = "";
//                    try {
//                        List<WebElement> imgs = product.findElements(By.tagName("img"));
//                        if (!imgs.isEmpty()) {
//                            WebElement imgTag = imgs.get(imgs.size() - 1);
//                            String rawSrc = imgTag.getAttribute("src");
//                            if (rawSrc.contains("url=")) {
//                                String encoded = rawSrc.split("url=")[1].split("&")[0];
//                                imageUrl = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
//                            }
//                        }
//                    } catch (Exception ignored) {}
//
//                    // Skip products with no name or link
//                    if (name.isEmpty() || link.isEmpty()) continue;
//
//                    // CHECK IF EXISTS
//                    Optional<Product> existing = productRepository.findByLink(link);
//                    if (existing.isPresent()) {
//                        Product existingProduct = existing.get();
//                        if (!existingProduct.getPrice().equals(price)) {
//                            existingProduct.setPrice(price);
//                            existingProduct.setImageUrl(imageUrl);
//                            productRepository.save(existingProduct);
//                        }
//                    } else {
//                        Product p = new Product();
//                        p.setName(name);
//                        p.setPrice(price);
//                        p.setLink(link);
//                        p.setImageUrl(imageUrl);
//                        productRepository.save(p);
//                    }
//
//                } catch (Exception e) {
//                    // Catch any unexpected error per product so the loop continues
//                    e.printStackTrace();
//                }
//            }
//
//            System.out.println("Selenium scraping finished: " + products.size() + " products processed.");
//
//        } catch (Exception e) {
//            e.printStackTrace();
//        } finally {
//            driver.quit();
//        }
//    }
//}