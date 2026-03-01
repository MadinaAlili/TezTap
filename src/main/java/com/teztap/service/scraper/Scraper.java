package com.teztap.service.scraper;

public interface Scraper<T> {
    T scrape(String url);
}
