package com.medicalocr.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * The unique indexes are the real constraint. AuthService checks first for a
 * friendly message, but two simultaneous registrations would both pass that
 * check — Mongo rejects the second, and GlobalExceptionHandler turns the
 * DuplicateKeyException into the same 409.
 *
 * Requires spring.data.mongodb.auto-index-creation: true, which is set.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class User {

    @Id
    private String id;

    @Indexed(unique = true)
    private String username;

    @Indexed(unique = true)
    private String email;

    /** BCrypt hash. Never serialised — no controller returns this entity. */
    private String password;

    private String firstName;
    private String lastName;

    @Builder.Default
    private boolean enabled = true;

    private LocalDateTime createdAt;
    private LocalDateTime lastLogin;
}
