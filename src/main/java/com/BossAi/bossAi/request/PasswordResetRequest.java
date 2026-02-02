package com.BossAi.bossAi.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetRequest {
    @Email
    @NotBlank
    private String email;
    @NotBlank
    private String newPassword;
}
