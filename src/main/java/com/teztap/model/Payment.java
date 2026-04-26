package com.teztap.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.experimental.Accessors;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Accessors(chain = true)
@Entity
@Table(name = "payments")
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod paymentMethod;

    @Column
    private String session_id;

    @CreationTimestamp
    private Date created;


    public enum PaymentStatus{
        PENDING,
        WAITING,
        PAID,
        FAILED,
        REFUNDED
    }

    public enum PaymentMethod {
        CARD,
        CASH_ON_DELIVERY,
        WALLET_BALANCE
    }
}
