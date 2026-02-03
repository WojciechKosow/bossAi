package com.BossAi.bossAi.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailChangeRequest {
    @Email
    @NotBlank
    private String newEmail;
    @NotBlank
    private String password;
}
