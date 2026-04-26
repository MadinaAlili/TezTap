package com.teztap.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "sub_orders")
@Getter
@Setter
@Accessors(chain = true)
public class SubOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Links back to the customer's main checkout/payment event
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order parentOrder;

    // The specific branch fulfilling this part of the order
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "market_branch_id", nullable = false)
    private MarketBranch marketBranch;

    // Optional: If you have a Courier entity, link it here.
    // If couriers are managed in a separate microservice, just use a Long courierId.
    @Column(name = "courier_id")
    private Long courierId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Order.OrderStatus status = Order.OrderStatus.PENDING;

    // The items that belong specifically to this branch
    @OneToMany(mappedBy = "subOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    // Helper method to keep bidirectional links in sync
    public void addItem(OrderItem item) {
        items.add(item);
        item.setSubOrder(this);
    }
}