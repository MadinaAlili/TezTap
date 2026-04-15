package com.teztap.service;

import com.google.maps.internal.PolylineEncoding;
import com.google.maps.model.LatLng;
import org.locationtech.jts.geom.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonToken;
import tools.jackson.core.type.WritableTypeId;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.jsontype.TypeSerializer;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;

public class GeometryUtils {
    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    public static Point createPoint(BigDecimal lng, BigDecimal lat) {
        Point point = FACTORY.createPoint(new Coordinate(lng.doubleValue(), lat.doubleValue()));
        point.setSRID(4326);
        return point;
    }

    public static LineString decodePolylineToLineString(String encodedPolyline) {
        if (encodedPolyline == null || encodedPolyline.isEmpty()) {
            return null;
        }

        // 1. Decode the string into a list of LatLng
        List<LatLng> decodedPath = PolylineEncoding.decode(encodedPolyline);

        // 2. Convert to JTS Coordinates
        // CRITICAL: JTS uses (X, Y) which translates to (Longitude, Latitude)
        Coordinate[] coordinates = decodedPath.stream()
                .map(latLng -> new Coordinate(latLng.lng, latLng.lat))
                .toArray(Coordinate[]::new);

        // 3. Create and return the LineString
        return FACTORY.createLineString(coordinates);
    }


    static public class LatLngSerializer extends ValueSerializer<Point> {
        @Override
        public void serialize(Point value, tools.jackson.core.JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
            gen.writeStartObject();
            gen.writeNumberProperty("lat", value.getY());
            gen.writeNumberProperty("lng", value.getX());
            gen.writeEndObject();
        }
        // NEW: Type-aware serialization (used by Redis with activateDefaultTyping)
        @Override
        public void serializeWithType(Point value, JsonGenerator gen, SerializationContext ctxt, TypeSerializer typeSer) throws JacksonException {
            // 1. Tell Jackson to start the object and automatically inject the type info (e.g., "@class": "...")
            WritableTypeId typeIdDef = typeSer.writeTypePrefix(gen, ctxt, typeSer.typeId(value, JsonToken.START_OBJECT));

            // 2. Write your custom fields.
            // Note: Do NOT call gen.writeStartObject() here, because writeTypePrefix() already did it!
            gen.writeNumberProperty("lat", value.getY());
            gen.writeNumberProperty("lng", value.getX());

            // 3. Tell Jackson to close the object
            typeSer.writeTypeSuffix(gen, ctxt, typeIdDef);
        }
    }

    public static class LatLngDeserializer extends ValueDeserializer<Point> {
        @Override
        public Point deserialize(tools.jackson.core.JsonParser p, tools.jackson.databind.DeserializationContext ctxt) throws JacksonException {
            try {
                ObjectNode node = p.readValueAsTree();
                double lat = node.get("lat").asDouble();
                double lng = node.get("lng").asDouble();
                return GeometryUtils.createPoint(new BigDecimal(lng), new BigDecimal(lat)); // x = lng, y = lat
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
