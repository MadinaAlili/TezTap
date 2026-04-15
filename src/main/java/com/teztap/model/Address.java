package com.teztap.model;

import com.teztap.service.GeometryUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;


@Embeddable // No @Entity, no @Id
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Address {
    @Column(columnDefinition = "geography(Point, 4326)")
    @JdbcTypeCode(SqlTypes.GEOMETRY)
    @JsonSerialize(using = GeometryUtils.LatLngSerializer.class)
    @JsonDeserialize(using = GeometryUtils.LatLngDeserializer.class)
    private Point location;

    private String fullAddress;
    private String additionalInfo;
}



// changed to embeddable

//@RequiredArgsConstructor
//@Data
//@Entity
//@Table(name = "addresses")
//public class Address {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Long id;
//    @Column(nullable = false, columnDefinition = "geography(Point, 4326)")
//    @JdbcTypeCode(SqlTypes.GEOMETRY)
//    private Point location;
//    @Column
//    private String fullAddress;
//    @Column
//    private String city;
//    @Column
//    private String district;
//    @Column
//    private String additionalInfo;
//}
