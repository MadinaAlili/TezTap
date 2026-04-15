package com.teztap.controller;

import com.teztap.model.User;
import com.teztap.repository.UserRepository;
import com.teztap.service.CourierService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/couriers")
@RequiredArgsConstructor
public class CourierController {

    private final CourierService courierService;
    private final UserRepository userRepository;

    @PostMapping("/setLocation")
    @PreAuthorize("hasRole('ROLE_COURIER')")
    public void setLocation(@RequestParam("latitude") double latitude, @RequestParam("longitude") double longitude, Authentication auth) {
        User user = userRepository.findByUsername(auth.getName()).orElseThrow();
        courierService.updateCourierGeo(user.getId(), latitude, longitude);
        courierService.setCourierOnline(user.getId());
    }

    @PostMapping("/online")
    @PreAuthorize("hasRole('ROLE_COURIER')")
    public void setLocation(Authentication auth) {
        User user = userRepository.findByUsername(auth.getName()).orElseThrow();
        courierService.setCourierOnline(user.getId());
    }

    @PostMapping("/accept")
    @PreAuthorize("hasRole('ROLE_COURIER')")
    public void acceptOrder(Authentication auth) {

    }
}
