package com.BossAi.bossAi.service.director.composition;

import lombok.*;

/**
 * Propozycja wielowarstwowej kompozycji dla jednej sceny.
 *
 * Generowana przez AutonomousCompositionDecider na podstawie reguł (np. TalkingHeadBg,
 * CtaOverlay, ProductReveal) i wysyłana do GPT w celu weryfikacji.
 *
 * GPT decyduje: accept=true → stosujemy SceneDirective, accept=false → fullscreen fallback.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompositionCandidate {

    /** Indeks sceny (= indeks w JustifiedCuts) */
    private int sceneIndex;

    /** Nazwa reguły, która wygenerowała kandydata */
    private String rule;

    /**
     * Typ kompozycji:
     *   pip       — picture-in-picture: primary (mniejszy) na tle background
     *   overlay   — primary fullscreen + overlay na wierzchu (logo, CTA, product)
     *   fullscreen — jedna warstwa (domyślne — kandydat nie jest akceptowany)
     */
    @Builder.Default
    private String composition = "fullscreen";

    /** Indeks assetu (z AssetProfile.index) pełniącego rolę primary */
    @Builder.Default
    private int primaryAssetIndex = -1;

    /** Indeks assetu pełniącego rolę tła (-1 = brak) */
    @Builder.Default
    private int backgroundAssetIndex = -1;

    /** Indeks assetu pełniącego rolę nakładki (-1 = brak) */
    @Builder.Default
    private int overlayAssetIndex = -1;

    /** Opis wizualny primary assetu (dla GPT kontekstu) */
    private String primaryDescription;

    /** Opis wizualny background assetu */
    private String backgroundDescription;

    /** Opis wizualny overlay assetu */
    private String overlayDescription;

    /** Fragment narracji w tym momencie */
    private String narrationText;

    /** Typ segmentu narracji (hook/setup/point/emphasis/climax/cta) */
    private String narrationType;

    /** Faza DNA (A=hook, B=problem, C=tension, D=reveal, E=transform, F=cta) */
    private String dnaBeat;

    /** Uzasadnienie reguły — dlaczego ta kompozycja ma sens */
    private String reasoning;
}
