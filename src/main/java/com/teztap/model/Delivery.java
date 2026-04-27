package com.teztap.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.locationtech.jts.geom.LineString;

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

    // Stores the assigned courier's username directly.
    // No Courier entity is used — the User table is the source of truth.
    // Add a DB migration: ALTER TABLE deliveries DROP COLUMN courier_id; ADD COLUMN courier_username VARCHAR(255);
    @Column(name = "courier_username")
    private String courierUsername;

    // polyline stored as PostGIS geometry
    @Column(columnDefinition = "geometry(LineString,4326)")
    private LineString route;

    private boolean delivered;

    @Column(columnDefinition = "text")
    private String note;

    @Column
    private LocalDateTime deliveryTime;
}
