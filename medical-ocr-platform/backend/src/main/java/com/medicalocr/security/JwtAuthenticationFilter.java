package com.medicalocr.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the bearer token and, if it verifies, populates the SecurityContext.
 *
 * It never rejects anything itself. An absent or bad token simply leaves the
 * context empty, and the authorisation rules in SecurityConfig decide what that
 * means for the path being requested — which keeps the 401 body consistent with
 * every other error, via the entry point rather than a response written here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String token = bearerToken(request);

        if (token != null
                && SecurityContextHolder.getContext().getAuthentication() == null
                && tokenProvider.validateToken(token)) {
            try {
                String username = tokenProvider.getUsernameFromToken(token);
                UserDetails user = userDetailsService.loadUserByUsername(username);

                // Load rather than trust: a token stays valid for 24 hours, so a
                // disabled or deleted account must stop working before then.
                if (user.isEnabled()) {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    user, null, user.getAuthorities());
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (UsernameNotFoundException ex) {
                log.debug("Token verified but the account is gone: {}", ex.getMessage());
            } catch (Exception ex) {
                // Never let an auth problem become a 500 — leave the context empty.
                log.debug("Could not establish authentication: {}", ex.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        chain.doFilter(request, response);
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return null;
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
