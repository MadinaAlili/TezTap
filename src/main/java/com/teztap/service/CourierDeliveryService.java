package com.teztap.service;

import com.teztap.dto.AddressDto;
import com.teztap.dto.CourierDeliveryDto;
import com.teztap.model.Address;
import com.teztap.model.Delivery;
import com.teztap.repository.DeliveryRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CourierDeliveryService {

    private final DeliveryRepository deliveryRepository;

    @Transactional(readOnly = true)
    public List<CourierDeliveryDto> getDeliveryHistory(String courierUsername) {
        return deliveryRepository.findAllByCourierUsername(courierUsername)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public CourierDeliveryDto getActiveDelivery(String courierUsername) {
        Delivery delivery = deliveryRepository
                .findActiveDeliveryByCourierUsername(courierUsername)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No active delivery for courier: " + courierUsername));
        return toDto(delivery);
    }

    private CourierDeliveryDto toDto(Delivery d) {
        Address branchAddress = d.getSubOrder().getMarketBranch().getAddress();
        AddressDto pickup = new AddressDto(
                branchAddress.getLocation().getX(),
                branchAddress.getLocation().getY(),
                branchAddress.getFullAddress(),
                branchAddress.getAdditionalInfo()
        );

        Address orderAddress = d.getSubOrder().getParentOrder().getOrderAddress();
        AddressDto dropoff = new AddressDto(
                orderAddress.getLocation().getX(),
                orderAddress.getLocation().getY(),
                orderAddress.getFullAddress(),
                orderAddress.getAdditionalInfo()
        );

        return new CourierDeliveryDto(
                d.getId(),
                d.getSubOrder().getParentOrder().getId(),
                d.getSubOrder().getId(),
                d.getSubOrder().getStatus().name(),
                d.isDelivered(),
                d.getDeliveryTime(),
                d.getSubOrder().getMarketBranch().getMarket().getName(),
                d.getNote(),
                pickup,
                dropoff,
                d.getDeliveryFee(),
                d.getDistanceKm(),
                d.getDurationMinutes()
        );
    }
}
