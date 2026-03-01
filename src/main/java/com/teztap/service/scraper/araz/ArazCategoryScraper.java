package com.teztap.service.scraper.araz;

import com.teztap.model.Category;
import com.teztap.repository.CategoryRepository;
import com.teztap.service.scraper.Scraper;
import jakarta.transaction.Transactional;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class ArazCategoryScraper implements Scraper<List<Category>> {

    private final CategoryRepository categoryRepository;
    private static final String BASE_URL = "https://www.arazmarket.az/az";

    public ArazCategoryScraper(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    @Transactional
    public List<Category> scrape(String url) {
        List<Category> allCategories = new ArrayList<>();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--disable-extensions");
//        options.setBinary("/usr/bin/google-chrome");

        WebDriver driver = new ChromeDriver(options);

        try {
            driver.get(url);
            Thread.sleep(2000); // wait for dropdown to render

            // MAIN CATEGORIES
            List<WebElement> mainLinks = driver.findElements(By.cssSelector("ul.products-dropdown_list_parent__6oiMQ > li > a"));

            try {
                JavascriptExecutor js = (JavascriptExecutor) driver;
                js.executeScript("document.elementFromPoint(1, 1).click();"); // click at x=1, y=1
                Thread.sleep(500); // wait for overlay to disappear
            } catch (Exception e) {
                System.out.println("Failed to click top-left corner: " + e.getMessage());
            }

            // Find the "Məhsullar" button and click it to open the dropdown
            try {
                WebElement menuButton = driver.findElement(By.cssSelector("div.header_products_btn__yC88K button"));
                menuButton.click();
                Thread.sleep(500); // wait for menu animation / load
            } catch (Exception e) {
                System.out.println("Failed to open categories menu: " + e.getMessage());
            }

            // Find the UL element containing categories
            WebElement categoriesList = driver.findElement(By.cssSelector("ul.products-dropdown_list_parent__6oiMQ"));

            // Scroll it slightly (e.g., 1000 pixels down)
            JavascriptExecutor js = (JavascriptExecutor) driver;
            js.executeScript("arguments[0].scrollBy(0, 1000);", categoriesList);

            // Get all li > a elements
            List<WebElement> categoryLinks = categoriesList.findElements(By.tagName("a"));

            // Iterate and print names and links
            for (WebElement link : categoryLinks) {
                WebElement span = link.findElement(By.tagName("span"));
                String name = span.getText().trim();       // category name
                String catUrl = link.getAttribute("href");    // category URL
                System.out.println(name + " -> " + catUrl);
                Category subCat = saveCategoryIfNotExists(name, catUrl);
                allCategories.add(subCat);
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            driver.quit();
        }

        return allCategories;
    }

    private Category saveCategoryIfNotExists(String name, String url) {
        Optional<Category> existing = categoryRepository.findByUrl(url);
        if (existing.isPresent()) return existing.get();

        Category cat = new Category();
        cat.setName(name);
        cat.setUrl(url);
        return categoryRepository.save(cat);
    }
}
