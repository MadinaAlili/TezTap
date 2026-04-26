package com.teztap.security;

import com.teztap.dto.AuthResponse;
import com.teztap.dto.LoginRequest;
import com.teztap.dto.MessageResponse;
import com.teztap.dto.RegisterRequest;
import com.teztap.model.Role;
import com.teztap.model.User;
import com.teztap.repository.RoleRepository;
import com.teztap.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;

    public MessageResponse register(RegisterRequest req) {
        if (userRepository.existsByUsername(req.username()))
            throw new RuntimeException("Username already taken");
        if (userRepository.existsByEmail(req.email()))
            throw new RuntimeException("Email already in use");

        User user = new User();
        user.setUsername(req.username());
        user.setEmail(req.email());
        user.setFullName(req.fullName());
        user.setPhoneNumber(req.phoneNumber());
        user.setPassword(passwordEncoder.encode(req.password()));

        Set<Role> roles = new HashSet<>();
        if (req.roles() == null || req.roles().isEmpty()) {
            // Default role
            roles.add(findRole(Role.RoleName.ROLE_USER));
        } else {
            req.roles().forEach(r -> {
                try {
                    roles.add(findRole(Role.RoleName.valueOf(r.toUpperCase())));
                }catch (IllegalArgumentException e){
                    roles.add(findRole(Role.RoleName.ROLE_USER));
                }
            });
        }

        user.setRoles(roles);
        userRepository.save(user);
        return new MessageResponse("User registered successfully");
    }

    public AuthResponse login(LoginRequest req) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.username(), req.password()));

        SecurityContextHolder.getContext().setAuthentication(auth);
        String token = jwtUtils.generateToken(auth);

        UserDetails userDetails = (UserDetails) auth.getPrincipal();
        assert userDetails != null;
        List<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();

        return new AuthResponse(token, req.username(), roles);
    }

    private Role findRole(Role.RoleName name) {
        return roleRepository.findByName(name)
                .orElseThrow(() -> new RuntimeException("Role not found: " + name));
    }
}