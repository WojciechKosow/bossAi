package com.BossAi.bossAi.request;

import com.BossAi.bossAi.entity.VideoStyle;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TikTokAdRequest {

    @NotBlank(message = "Prompt cannot be blank")
    @Size(min = 10, max = 2000, message = "Prompt has to be from 10 to 2000 chars long")
    private String prompt;

    private List<UUID> assetIds;

    private MultipartFile musicFile;

    @Enumerated(EnumType.STRING)
    private VideoStyle style;

    /**
     * Czy pipeline ma próbować ponownie wykorzystać wcześniej wygenerowane assety
     * (obrazy, wideo) dopasowane tematycznie do nowego promptu.
     * Domyślnie true — oszczędza kredyty. Dostępne dla planów > BASIC.
     */
    private boolean reuseAssets = true;
}
