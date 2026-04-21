package com.BossAi.bossAi.service.director;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

/**
 * Sparsowana instrukcja usera na poziomie pojedynczej sceny.
 *
 * SceneDirective opisuje CO ma się znaleźć w danej scenie:
 *   - Jakie warstwy (background, primary, overlay)
 *   - Skąd wziąć assety (user podał vs wygenerować)
 *   - Jak je skomponować (fullscreen, overlay, picture-in-picture)
 *   - Kiedy ta scena ma wystąpić
 *
 * Generowany przez UserIntentParser (GPT parsuje prompt usera).
 * Feedowany do ScriptStep, EdlGeneratorService, CutEngine.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SceneDirective {

    @JsonProperty("scene_index")
    private int sceneIndex;

    @JsonProperty("scene_label")
    private String sceneLabel;

    @JsonProperty("description")
    private String description;

    @JsonProperty("layers")
    private List<LayerDirective> layers;

    @JsonProperty("composition")
    @Builder.Default
    private String composition = "fullscreen";

    @JsonProperty("timing")
    @Builder.Default
    private String timing = "auto";

    @JsonProperty("duration_hint_ms")
    private int durationHintMs;

    @JsonProperty("transition_in")
    private String transitionIn;

    @JsonProperty("transition_out")
    private String transitionOut;

    @JsonProperty("user_instruction")
    private String userInstruction;

    /**
     * Pojedyncza warstwa w scenie.
     *
     * Każda scena może mieć wiele warstw:
     *   layer 0 = BACKGROUND (tło, np. wygenerowane stock footage)
     *   layer 1 = PRIMARY (główny kontent, np. filmik usera)
     *   layer 2 = OVERLAY (nałożony element, np. gif, animacja)
     *
     * Każda warstwa ma źródło:
     *   PROVIDED — user podał asset (referencja przez assetIndex lub description match)
     *   GENERATE — user chce żeby system wygenerował (image/video z AI)
     *   AUTO — system decyduje
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LayerDirective {

        @JsonProperty("layer_index")
        @Builder.Default
        private int layerIndex = 0;

        @JsonProperty("role")
        @Builder.Default
        private String role = "primary";

        @JsonProperty("source")
        @Builder.Default
        private String source = "auto";

        @JsonProperty("asset_index")
        @Builder.Default
        private int assetIndex = -1;

        @JsonProperty("asset_description")
        private String assetDescription;

        @JsonProperty("generation_prompt")
        private String generationPrompt;

        @JsonProperty("generation_type")
        private String generationType;

        @JsonProperty("effect")
        private String effect;

        @JsonProperty("opacity")
        @Builder.Default
        private double opacity = 1.0;

        public boolean isProvided() {
            return "provided".equals(source) && assetIndex >= 0;
        }

        public boolean isGenerate() {
            return "generate".equals(source) && generationPrompt != null;
        }
    }

    // ─── Helpers ───────────────────────────────────────────────

    public boolean hasMultipleLayers() {
        return layers != null && layers.size() > 1;
    }

    public LayerDirective getPrimaryLayer() {
        if (layers == null || layers.isEmpty()) return null;
        return layers.stream()
                .filter(l -> "primary".equals(l.getRole()))
                .findFirst()
                .orElse(layers.get(0));
    }

    public LayerDirective getBackgroundLayer() {
        if (layers == null) return null;
        return layers.stream()
                .filter(l -> "background".equals(l.getRole()))
                .findFirst()
                .orElse(null);
    }

    public boolean needsGeneration() {
        return layers != null && layers.stream().anyMatch(LayerDirective::isGenerate);
    }
}
