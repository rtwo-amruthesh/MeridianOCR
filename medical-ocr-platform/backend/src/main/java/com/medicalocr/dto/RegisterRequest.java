package com.medicalocr.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    @NotBlank(message = "Choose a username.")
    @Size(min = 3, max = 40, message = "Username must be 3 to 40 characters.")
    @Pattern(regexp = "^[A-Za-z0-9._-]+$",
             message = "Username can use letters, numbers, dot, underscore and hyphen.")
    private String username;

    @NotBlank(message = "Enter an email address.")
    @Email(message = "That email address doesn't look right.")
    @Size(max = 254)
    private String email;

    /**
     * Length is the control that actually matters. A composition rule pushes
     * people towards short predictable passwords, so there isn't one.
     */
    @NotBlank(message = "Choose a password.")
    @Size(min = 12, max = 128, message = "Password must be at least 12 characters.")
    private String password;

    @Size(max = 60)
    private String firstName;

    @Size(max = 60)
    private String lastName;
}
