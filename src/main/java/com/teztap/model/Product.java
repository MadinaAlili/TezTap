package com.teztap.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.sql.Date;

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
    private BigDecimal originalPrice;

    @Column(nullable = false, unique = true, length = 1000)
    private String link;

    @Column(length = 1000)
    private String imageUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    @JsonBackReference
    private Category category;

    @Column
    private BigDecimal discountPrice;

    @Column
    private BigDecimal discountPercentage;

    @UpdateTimestamp
    private Date updated;

    @CreationTimestamp
    private Date created;

    @ManyToOne
    @JoinColumn(name = "market_id")
    private Market market;

    // Constructors
    public Product() {}

    public Product(String name, BigDecimal originalPrice, String link, String imageUrl, Category category, BigDecimal discountPrice, BigDecimal discountPercentage) {
        this.name = name;
        this.originalPrice = originalPrice;
        this.link = link;
        this.imageUrl = imageUrl;
        this.category = category;
        this.discountPrice = discountPrice;
        this.discountPercentage = discountPercentage;
    }
}
