package com.teztap.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String price;

    @Column(nullable = false, unique = true, length = 1000)
    private String link;

    @Column(length = 1000)
    private String imageUrl;

    // Constructors
    public Product() {}

    public Product(String name, String price, String link, String imageUrl) {
        this.name = name;
        this.price = price;
        this.link = link;
        this.imageUrl = imageUrl;
    }

    // Getters & Setters

}
