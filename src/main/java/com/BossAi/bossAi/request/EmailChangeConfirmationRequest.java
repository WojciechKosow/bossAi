package com.BossAi.bossAi.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EmailChangeConfirmationRequest {

    @NotBlank
    private String password;
}
