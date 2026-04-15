package com.teztap.model;

import jakarta.persistence.*;
import lombok.Data;
import org.locationtech.jts.geom.Point;

@Entity
@Data
@Table(name = "couriers")
public class Courier {
    @Id
    private Long id; // This will match the User ID exactly

    @Column(columnDefinition = "geography(Point, 4326)")
    private Point lastLocation;

//    @Column(name = "current_market_id")
//    private Long currentMarketId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "id") // The FK is the PK
    private User user;
}
