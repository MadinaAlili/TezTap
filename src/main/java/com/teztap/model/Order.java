package com.teztap.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Entity
@Data
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.PENDING;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Embedded
    private Address orderAddress;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "market_branch_id")
    private MarketBranch marketBranch;

    @CreationTimestamp
    private Date created;

    @UpdateTimestamp
    private Date updated;

    @Column(columnDefinition = "text")
    private String deliveryNote;

    public enum OrderStatus {
        PENDING,
        AWAITING_PAYMENT,
        PAID,
        PAYMENT_FAILED,
        WAITING_FOR_COURIER,
        WAITING_FOR_SHIPMENT,
        ON_THE_WAY,
        SHIPPED,
        DELIVERED,
        CANCELLED
    }
}
