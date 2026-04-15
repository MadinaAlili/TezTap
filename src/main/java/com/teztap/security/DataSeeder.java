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
            System.out.println(name);
            if (roleRepository.findByName(name).isEmpty()) {
                System.out.println(roleRepository.findByName(name));
                Role role = new Role();
                role.setName(name);
                try {
                    roleRepository.save(role);
                }catch(Exception ignore){}
            }
        }
    }
}