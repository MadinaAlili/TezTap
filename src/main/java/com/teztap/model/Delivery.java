package com.teztap.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.locationtech.jts.geom.LineString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "deliveries")
@Getter
@Setter
@Accessors(chain = true)
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sub_order_id", referencedColumnName = "id", nullable = false)
    private SubOrder subOrder;

    @Column(name = "courier_username")
    private String courierUsername;

    @Column(columnDefinition = "geometry(LineString,4326)")
    private LineString route;

    private boolean delivered;

    @Column(columnDefinition = "text")
    private String note;

    @Column
    private LocalDateTime deliveryTime;

    // ── Pricing ───────────────────────────────────────────────────────────────
    // Stored at delivery-creation time so the fare is locked in even if the
    // pricing config changes later. Each sub-order has its own route so each
    // delivery has its own fare — do not store this on Order.

    /** Total fare charged to the customer for this delivery leg (AZN) */
    @Column(precision = 10, scale = 2)
    private BigDecimal deliveryFee;

    /** Road distance in km — stored for courier display and record keeping */
    @Column
    private Double distanceKm;

    /** Estimated travel time in minutes — stored for courier display */
    @Column
    private Double durationMinutes;

    // DB migration:
    // ALTER TABLE deliveries ADD COLUMN delivery_fee NUMERIC(10,2);
    // ALTER TABLE deliveries ADD COLUMN distance_km DOUBLE PRECISION;
    // ALTER TABLE deliveries ADD COLUMN duration_minutes DOUBLE PRECISION;
}
