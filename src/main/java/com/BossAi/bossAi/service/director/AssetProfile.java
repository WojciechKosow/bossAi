package com.BossAi.bossAi.service.director;

import lombok.*;

import java.util.List;
import java.util.UUID;

/**
 * Profil wizualny assetu — daje GPT "oczy".
 *
 * Zamiast traktować assety jako "kolejny plik VIDEO/IMAGE",
 * AssetProfile opisuje CO jest na assecie, JAK wygląda,
 * i JAKĄ ROLĘ sugeruje w montażu.
 *
 * Generowany przez AssetAnalyzer na podstawie:
 *   - metadanych (type, duration, prompt/opis)
 *   - GPT Vision (jeśli dostępne)
 *   - kontekstu usera (orderIndex, nazwa pliku)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetProfile {

    /** ID assetu — mapowanie 1:1 z Asset.id */
    private UUID assetId;

    /** Indeks w kolejności assetów usera (z orderIndex) */
    private int index;

    /** Typ assetu: VIDEO, IMAGE */
    private String assetType;

    /**
     * Opis wizualny treści assetu.
     * Np. "Logo animation with blue gradient background"
     * lub "Close-up of a person talking to camera, indoor setting"
     */
    private String visualDescription;

    /**
     * Sugerowana rola montażowa.
     * Wartości: intro, hook, content, b-roll, product-shot, testimonial,
     *           transition, outro, cta, background
     */
    private String suggestedRole;

    /**
     * Nastrój/klimat assetu.
     * Np. "energetic", "calm", "dramatic", "professional", "playful"
     */
    private String mood;

    /**
     * Złożoność wizualna (0.0-1.0).
     * Niski = statyczny, prosty. Wysoki = dynamiczny, pełen detali.
     * Wpływa na wybór efektów: proste → ken_burns, złożone → cut/minimal.
     */
    private double visualComplexity;

    /**
     * Tagi opisujące zawartość — używane do matchowania z narracja.
     * Np. ["logo", "brand", "animation"] lub ["person", "talking", "indoor"]
     */
    @Builder.Default
    private List<String> tags = List.of();

    /**
     * Czas trwania w sekundach (tylko VIDEO, null dla IMAGE).
     */
    private Integer durationSeconds;

    /**
     * Czy asset nadaje się na pętlę (loop-friendly ending)?
     * True jeśli koniec przechodzi płynnie do początku.
     */
    private boolean loopable;

    /**
     * Dominujące kolory (hex). Opcjonalne.
     * Mogą wpływać na color_grade w EditDna.
     */
    @Builder.Default
    private List<String> dominantColors = List.of();

    /**
     * Czy asset nadaje się jako tło (blur behind primary).
     * True dla: b-roll, background, scenery, ambient footage, lifestyle.
     * False dla: testimonial, product-shot, cta, logo.
     */
    @Builder.Default
    private boolean canBeBackground = false;

    /**
     * Czy asset nadaje się jako nakładka (overlay on top of primary).
     * True dla: cta, logo, product-shot, button, outro.
     * False dla: b-roll, testimonial, content.
     */
    @Builder.Default
    private boolean canBeOverlay = false;

    /**
     * Siła wizualna — jak bardzo asset "domaga się" pierwszego planu (0.0-1.0).
     * Wysoki = chce być primary (person talking, product reveal).
     * Niski = pasuje jako tło (b-roll, ambient).
     */
    @Builder.Default
    private double visualWeight = 0.5;
}
