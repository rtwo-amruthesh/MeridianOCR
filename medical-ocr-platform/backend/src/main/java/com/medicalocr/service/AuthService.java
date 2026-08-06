package com.medicalocr.service;

import com.medicalocr.dto.AuthResponse;
import com.medicalocr.dto.LoginRequest;
import com.medicalocr.dto.RegisterRequest;
import com.medicalocr.exception.ConflictException;
import com.medicalocr.exception.NotFoundException;
import com.medicalocr.model.User;
import com.medicalocr.repository.UserRepository;
import com.medicalocr.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationManager authenticationManager;

    public AuthResponse register(RegisterRequest request) {
        String username = request.getUsername().trim();
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);

        // Friendly check first; the unique indexes still cover the race, and
        // GlobalExceptionHandler turns a DuplicateKeyException into the same 409.
        if (userRepository.existsByUsername(username)) {
            throw new ConflictException("That username is taken.");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("That email is already registered.");
        }

        User user = User.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build();
        userRepository.save(user);
        log.info("Registered user {}", username);

        return tokenFor(user);
    }

    public AuthResponse login(LoginRequest request) {
        // Throws AuthenticationException on bad credentials; the handler maps it
        // to a 401 with a message that doesn't reveal which half was wrong.
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new NotFoundException("No account for that username."));

        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        return tokenFor(user);
    }

    private AuthResponse tokenFor(User user) {
        return AuthResponse.builder()
                .token(jwtTokenProvider.generateToken(user.getUsername()))
                .type("Bearer")
                .username(user.getUsername())
                .email(user.getEmail())
                .expiresInSeconds(jwtTokenProvider.getJwtExpiration() / 1000)
                .build();
    }
}
