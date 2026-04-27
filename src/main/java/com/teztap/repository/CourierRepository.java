package com.teztap.repository;

import com.teztap.model.Courier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CourierRepository extends JpaRepository<Courier, Long> {

    @Query("SELECT c FROM Courier c WHERE c.user.username = :username")
    Optional<Courier> findByUserUsername(@Param("username") String username);
}
