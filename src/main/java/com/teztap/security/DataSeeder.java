package com.teztap.security;

import com.teztap.model.Role;
import com.teztap.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final RoleRepository roleRepository;

    @Override
    public void run(ApplicationArguments args) {
        for (Role.RoleName name : Role.RoleName.values()) {
            if (roleRepository.findByName(name).isEmpty()) {
                Role role = new Role();
                role.setName(name);
                roleRepository.save(role);
            }
        }
    }
}