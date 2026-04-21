package com.BossAi.bossAi.service.director;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

/**
 * Sparsowana intencja montażowa usera.
 *
 * Zamiast traktować prompt jako "tekst do wygenerowania scenariusza",
 * UserEditIntent rozbija go na strukturalne decyzje montażowe:
 *   - KTÓRY asset ma jaką ROLĘ (intro, content, outro)
 *   - GDZIE co umieścić na timeline
 *   - JAKIE pacing/styl preferuje user
 *
 * Generowany przez UserIntentParser (GPT parsuje prompt).
 * Feedowany do CutEngine, EdlGenerator, ScriptStep.
 *
 * Jeśli user nie podał żadnych wskazówek montażowych,
 * pola będą mieć sensowne defaulty (auto).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserEditIntent {

    /**
     * Ogólny cel filmu w 1 zdaniu.
     * Np. "educational video about top 5 AI tools"
     */
    @JsonProperty("overall_goal")
    private String overallGoal;

    /**
     * Lista assetów z przypisanymi rolami/pozycjami.
     * Każdy element = 1 asset usera + jego rola w montażu.
     */
    @Builder.Default
    @JsonProperty("placements")
    private List<AssetPlacement> placements = List.of();

    /**
     * Preferencja tempa montażu.
     * "fast", "moderate", "slow", "auto" (domyślnie auto — system decyduje).
     */
    @Builder.Default
    @JsonProperty("pacing_preference")
    private String pacingPreference = "auto";

    /**
     * Wskazówki strukturalne wyciągnięte z prompta.
     * Np. ["intro first", "show product in middle", "end with CTA"]
     */
    @Builder.Default
    @JsonProperty("structure_hints")
    private List<String> structureHints = List.of();

    /**
     * Czy user jawnie kontroluje kolejność assetów?
     * true = user powiedział "ten pierwszy, ten drugi..."
     * false = user pozwala systemowi/GPT decydować
     */
    @JsonProperty("user_controls_order")
    private boolean userControlsOrder;

    /**
     * Styl montażowy wyciągnięty z prompta (jeśli podany).
     * Np. "cinematic", "fast-paced", "chill", "professional"
     * Null jeśli user nie podał.
     */
    @JsonProperty("editing_style")
    private String editingStyle;

    /**
     * Preferowana długość końcowego filmu (ms).
     * 0 = auto (system decyduje na podstawie treści).
     */
    @JsonProperty("target_duration_ms")
    private int targetDurationMs;

    /**
     * Uzasadnienie parsowania — dlaczego GPT tak zinterpretował prompt.
     * Debugowe, nie wpływa na logikę.
     */
    @JsonProperty("reasoning")
    private String reasoning;

    /**
     * Szczegółowe instrukcje per scena — co dokładnie ma się znaleźć w każdej scenie.
     * Każda SceneDirective opisuje warstwy (background/primary/overlay),
     * źródła assetów (provided/generate), kompozycję, timing.
     *
     * Jeśli user opisał sceny w prompcie (np. "pierwsza scena = intro z giełdą w tle"),
     * to GPT parsuje to do SceneDirectives.
     * Pusta lista = user nie opisał konkretnych scen → system decyduje.
     */
    @Builder.Default
    @JsonProperty("scene_directives")
    private List<SceneDirective> sceneDirectives = List.of();

    // ─── Placement per asset ───────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssetPlacement {

        /** Indeks assetu w liście custom media (0-based, = orderIndex) */
        @JsonProperty("asset_index")
        private int assetIndex;

        /**
         * Rola montażowa tego assetu.
         * Wartości: intro, hook, content, b-roll, product-shot,
         *           testimonial, transition, outro, cta, background, auto
         */
        @JsonProperty("role")
        private String role;

        /**
         * Gdzie na timeline umieścić ten asset.
         * "beginning", "middle", "end", "at_topic_change",
         * "after_hook", "before_cta", "auto"
         */
        @JsonProperty("timing")
        private String timing;

        /**
         * Wskazówka ile ms powinien trwać ten asset.
         * 0 = auto (system/GPT decyduje).
         */
        @JsonProperty("duration_hint_ms")
        private int durationHintMs;

        /**
         * Opis roli z prompta usera (cytat lub parafraza).
         * Np. "user said: 'this should be the intro'"
         */
        @JsonProperty("user_instruction")
        private String userInstruction;
    }

    // ─── Helper methods ────────────────────────────────────────

    /**
     * Czy user podał JAKIEKOLWIEK wskazówki montażowe?
     * Jeśli nie — system działa w trybie auto (jak dotychczas).
     */
    public boolean hasExplicitInstructions() {
        if (sceneDirectives != null && !sceneDirectives.isEmpty()) {
            return true;
        }
        if (placements != null && !placements.isEmpty()) {
            return placements.stream().anyMatch(p ->
                    p.getRole() != null && !"auto".equals(p.getRole()));
        }
        return structureHints != null && !structureHints.isEmpty();
    }

    /**
     * Znajdź placement dla danego indeksu assetu.
     */
    public AssetPlacement getPlacementForAsset(int assetIndex) {
        if (placements == null) return null;
        return placements.stream()
                .filter(p -> p.getAssetIndex() == assetIndex)
                .findFirst()
                .orElse(null);
    }

    /**
     * Znajdź assety z daną rolą.
     */
    public List<AssetPlacement> getPlacementsByRole(String role) {
        if (placements == null) return List.of();
        return placements.stream()
                .filter(p -> role.equals(p.getRole()))
                .toList();
    }

    /**
     * Czy user opisał konkretne sceny z warstwami?
     */
    public boolean hasSceneDirectives() {
        return sceneDirectives != null && !sceneDirectives.isEmpty();
    }

    /**
     * Znajdź SceneDirective dla danego indeksu sceny.
     */
    public SceneDirective getSceneDirective(int sceneIndex) {
        if (sceneDirectives == null) return null;
        return sceneDirectives.stream()
                .filter(sd -> sd.getSceneIndex() == sceneIndex)
                .findFirst()
                .orElse(null);
    }

    /**
     * Czy któraś scena wymaga generowania assetów?
     */
    public boolean needsAssetGeneration() {
        return sceneDirectives != null && sceneDirectives.stream()
                .anyMatch(SceneDirective::needsGeneration);
    }
}
