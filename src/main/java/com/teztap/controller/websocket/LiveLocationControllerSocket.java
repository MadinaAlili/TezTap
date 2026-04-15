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
        CustomUserDetails user = (CustomUserDetails) auth.getPrincipal();
        String username = user.getUsername();
        System.err.println("Received location update from [reverse lng<->lat]" + username + ": " + locationRequest);
        // Redis store Point object from data geo which is the reverse order of Jts, so we save in reverse
        Point point = new Point(locationRequest.lat().doubleValue(), locationRequest.lng().doubleValue());

        courierService.updateCourierLocation(username, point);
    }

    @MessageMapping("/hello")        // client sends to /app/hello
    @SendTo("/topic/test")          // server sends to /topic/test
    public String test(String message) {
        System.out.println("Received: " + message);
        return "world";
    }
}
