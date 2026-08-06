package com.medicalocr.security;

import com.medicalocr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The piece that was missing.
 *
 * AuthService calls authenticationManager.authenticate(...), but without a
 * UserDetailsService bean nothing told Spring Security how to load a user from
 * Mongo — so registration worked (it writes through the repository directly)
 * and login could never succeed.
 */
@Service
@RequiredArgsConstructor
public class MongoUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsername(username)
                .map(user -> org.springframework.security.core.userdetails.User
                        .withUsername(user.getUsername())
                        .password(user.getPassword())
                        .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                        .disabled(!user.isEnabled())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("No account for that username."));
    }
}
