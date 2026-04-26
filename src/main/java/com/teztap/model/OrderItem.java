package com.teztap.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Entity
@Data
@Table(name = "order_items")
@Accessors(chain = true)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sub_order_id", nullable = false)
    private SubOrder subOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "market_branch_id")
    private MarketBranch marketBranch;

    @Column(nullable = false)
    private Integer quantity;

    // snapshot the price at time of order — important!
    @Column(nullable = false)
    private BigDecimal priceAtPurchase;
}
