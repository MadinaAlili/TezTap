package com.teztap.model;

import jakarta.persistence.*;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "customers")
public class Customer {
    @Id
    private Long id; // This will match the User ID exactly

    @Column(columnDefinition = "geography(Point, 4326)")
    private Point lastLocation;

    @Embedded
    private Address defaultAddress;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "id") // The FK is the PK
    private User user;
}
