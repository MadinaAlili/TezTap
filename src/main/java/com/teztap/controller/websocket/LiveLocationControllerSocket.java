package com.teztap.controller.websocket;

import com.teztap.dto.LocationRequest;
import com.teztap.security.CustomUserDetails;
import com.teztap.service.CourierService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class LiveLocationControllerSocket {

    private final RedisTemplate<String, Object> redisTemplate;
    private final CourierService courierService;

    @MessageMapping("/location")
    public void location(LocationRequest locationRequest, Authentication auth) {
        if (auth == null) {
            System.err.println("[LiveLocationSocket] ERROR: auth is null — JWT not set on session.");
            return;
        }

        CustomUserDetails user = (CustomUserDetails) auth.getPrincipal();
        String username = user.getUsername();

        if (locationRequest == null) {
            System.err.println("[LiveLocationSocket] ERROR: locationRequest is null for courier '" + username + "'");
            return;
        }

        // NOTE: Spring GeoPoint stores as Point(x, y). Redis GEO uses (longitude, latitude).
        // We intentionally swap here so that:
        //   point.getX() = lat  (what we stored)
        //   point.getY() = lng  (what we stored)
        // CourierService.updateCourierLocation() sends point.getX() as courierLng and
        // point.getY() as courierLat in DeliveryStatusResponse — which is inverted.
        // TODO: align this convention. For now both sides are consistently wrong in the
        // same direction so the client receives swapped coords — fix on client side or
        // flip here and in DeliveryStatusResponse field names.
        Point point = new Point(locationRequest.lat().doubleValue(), locationRequest.lng().doubleValue());

        try {
            courierService.updateCourierLocation(username, point);
        } catch (Exception e) {
            System.err.println("[LiveLocationSocket] ERROR in updateCourierLocation for '" + username + "': "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @MessageMapping("/hello")
    @SendTo("/topic/test")
    public String test(String message) {
        System.err.println("[LiveLocationSocket] /hello ping: " + message);
        return "world";
    }
}
