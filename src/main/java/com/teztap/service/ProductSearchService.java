package com.teztap.service;

import com.teztap.dto.IndexResponse;
import com.teztap.dto.ProductDTO;
import com.teztap.dto.SearchResponse;
import com.teztap.dto.TagResponse;
import com.teztap.kafka.kafkaEventDto.ProductCreatedEvent;
import com.teztap.model.Product;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class ProductSearchService {

    private final RestTemplate restTemplate = new RestTemplate();
    @Value("${search.engine.url}")
    private String SEARCH_ENGINE_URL;

    @KafkaListener(topics = "product-created", groupId = "product-created-group")
    public IndexResponse indexProduct(ProductCreatedEvent event) {
        try{
            return indexProduct(event.products());
        }catch (Exception e){
            System.err.println("[ProductSearchService] Connection Failed with Product Search Service");
        }
        return null;
    }

    public IndexResponse indexProduct(List<ProductDTO> products) throws ResourceAccessException {
        Map<String, Object> body = Map.of(
                "products",
                products.stream()
                        .map(p -> Map.of(
                                "id", p.id(),
                                "name", p.name(),
                                "image_url", p.imageUrl()
                        ))
                        .toList()
        );

        ResponseEntity<IndexResponse> response = restTemplate.postForEntity(
                SEARCH_ENGINE_URL+"/products/index",
                body,
                IndexResponse.class
        );
        return response.getBody();
    }

    public SearchResponse imageSearch(String base64, int top_k) {
        Map<String, Object> body = Map.of("image_base64", base64, "top_k", top_k);
        ResponseEntity<SearchResponse> response = restTemplate.postForEntity(
                SEARCH_ENGINE_URL+"/search",
                body,
                SearchResponse.class
        );
        return response.getBody();
    }

    public SearchResponse textSearch(String query, int top_k) {
        Map<String, Object> body = Map.of("query", query, "top_k", top_k);
        ResponseEntity<SearchResponse> response = restTemplate.postForEntity(
                SEARCH_ENGINE_URL+"/search/text",
                body,
                SearchResponse.class
        );
        return response.getBody();
    }

    public TagResponse getTags(Long productId){
        ResponseEntity<TagResponse> response = restTemplate.getForEntity(
                SEARCH_ENGINE_URL + "/products/" + productId.toString() +"/tags",
                TagResponse.class
        );
        return response.getBody();
    }

    public ProductDTO toDTO(Product product) {
        return new ProductDTO(
                product.getId(),
                product.getName(),
                product.getOriginalPrice(),
                product.getDiscountPrice(),
                product.getDiscountPercentage(),
                product.getLink(),
                product.getImageUrl(),
                product.getCategory() != null ? product.getCategory().getId() : null,
                product.getMarket() != null ? product.getMarket().getId() : null
        );
    }
}
