package com.teztap.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Date;
import java.util.List;

@Entity
@Data
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true, length = 1000)
    private String url;

    // Optional parent
//    @ManyToOne
//    private Category parent;

    @OneToMany(mappedBy = "category")
    private List<Product> products;

    @CreationTimestamp
    private Date created;

}
