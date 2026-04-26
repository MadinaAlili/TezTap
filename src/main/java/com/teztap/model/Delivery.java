package com.teztap.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
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

    // 🚨 ARCHITECTURE FIX: Now points to the specific branch's portion of the order
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sub_order_id", referencedColumnName = "id", nullable = false)
    private SubOrder subOrder;

    // 🚨 CARDINALITY FIX: A courier can do many deliveries
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "courier_id", referencedColumnName = "id")
    private Courier courier;

    // polyline stored as PostGIS geometry
    @Column(columnDefinition = "geometry(LineString,4326)")
    private LineString route;

    private boolean delivered;

    @Column(columnDefinition = "text")
    private String note;

    @Column
    private LocalDateTime deliveryTime;
}
