package com.teztap.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;

@Entity
@Data
@RequiredArgsConstructor
@Accessors(chain = true)
public class MarketBranch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String googlePlaceId;

    @Column(nullable = false)
    private String description;

    @Embedded
    private Address address;

    @Column
    private String phoneNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<DayOfWeek, List<TimeRange>> openingHours;

    @Column(nullable = false)
    private boolean open24_7;

    @Column(nullable = false)
    private String plusCode;

    @Column
    private boolean active = true;

    @ManyToOne
    @JoinColumn(name = "market_id")
    private Market market;
}
