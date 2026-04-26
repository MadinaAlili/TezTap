package com.teztap.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "markets")
@Data
@RequiredArgsConstructor
public class Market {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 150)
    private String name;

    @Column
    private String displayName;

    @Column(nullable = false, unique = true, length = 500)
    private String baseUrl;

    @Column(nullable = false)
    private String categoryScrapingBaseUrl;

    @Column
    private String logoUrl;

    @Column(nullable = false)
    private Boolean active = true;

    @CreationTimestamp
    private LocalDateTime created;

}
