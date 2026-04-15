package com.teztap.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.LineString;

import java.time.LocalDateTime;

@Entity
@Data
@RequiredArgsConstructor
public class Delivery {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "order_id", referencedColumnName = "id")
    private Order order;

    @OneToOne
    @JoinColumn(name = "courier_id", referencedColumnName = "id")
    private Courier courier;

    // polyline stored as PostGIS geometry
    @Column(columnDefinition = "geometry(LineString,4326)")
    private LineString route;

    private boolean delivered;

    @Column
    private String note;

    @Column
    private LocalDateTime deliveryTime;
}
