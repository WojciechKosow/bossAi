package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.entity.VideoStyle;
import com.BossAi.bossAi.service.audio.AudioAnalysisResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Przypisuje efekty i przejścia do cutów.
 *
 * v2 — muzycznie świadomy, niedeterministyczny:
 *   - Efekty dobierane z pul (pools) na podstawie sekcji muzycznej + mood + danceability
 *   - Losowy wybór z puli → każde wideo jest inne
 *   - Sekcja "drop" → agresywne efekty (FAST_ZOOM, SHAKE, BOUNCE)
 *   - Sekcja "build" → narastające (ZOOM_IN, ZOOM_IN_OFFSET)
 *   - Sekcja "quiet/intro/outro" → spokojne (PAN_*, DRIFT, ZOOM_OUT)
 *   - Zapobiega powtórzeniom (nie ten sam efekt 2x z rzędu)
 */
@Slf4j
@Component
public class EffectAssigner {

    // =========================================================================
    // EFFECT POOLS — dobierane na podstawie sekcji muzycznej + energii
    // =========================================================================

    /** Agresywne efekty na dropy / peak / high energy */
    private static final List<EffectType> POOL_AGGRESSIVE = List.of(
            EffectType.FAST_ZOOM, EffectType.SHAKE, EffectType.BOUNCE,
            EffectType.ZOOM_IN_OFFSET, EffectType.ZOOM_IN
    );

    /** Narastające efekty na build-up / medium energy */
    private static final List<EffectType> POOL_BUILDING = List.of(
            EffectType.ZOOM_IN, EffectType.ZOOM_IN_OFFSET, EffectType.PAN_LEFT,
            EffectType.PAN_RIGHT, EffectType.PAN_UP, EffectType.BOUNCE
    );

    /** Spokojne efekty na quiet / intro / outro / low energy */
    private static final List<EffectType> POOL_CALM = List.of(
            EffectType.PAN_LEFT, EffectType.PAN_RIGHT, EffectType.PAN_UP,
            EffectType.PAN_DOWN, EffectType.DRIFT, EffectType.ZOOM_OUT
    );

    /** Filmowe efekty (cinematic / luxury) */
    private static final List<EffectType> POOL_CINEMATIC = List.of(
            EffectType.PAN_LEFT, EffectType.PAN_RIGHT, EffectType.PAN_UP,
            EffectType.PAN_DOWN, EffectType.ZOOM_IN, EffectType.ZOOM_OUT,
            EffectType.DRIFT
    );

    /** Edukacyjne — minimalne ruchy, czytelność */
    private static final List<EffectType> POOL_EDUCATIONAL = List.of(
            EffectType.ZOOM_OUT, EffectType.PAN_LEFT, EffectType.PAN_RIGHT,
            EffectType.DRIFT, EffectType.NONE
    );

    // =========================================================================
    // TRANSITION POOLS
    // =========================================================================

    private static final List<String> TRANSITIONS_AGGRESSIVE = List.of(
            "fadewhite", "wipeleft", "wiperight", "wipeup", "wipedown",
            "slideleft", "slideright", "slideup", "slidedown"
    );

    private static final List<String> TRANSITIONS_SMOOTH = List.of(
            "fade", "dissolve", "fadeblack", "smoothleft", "smoothright"
    );

    private static final List<String> TRANSITIONS_MINIMAL = List.of(
            "cut", "fade"
    );

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Przypisuje efekty do cutów z uwzględnieniem muzyki.
     * Jeśli audioAnalysis == null → fallback na starą logikę (styl + energy).
     */
    public void applyEffects(DirectorPlan plan, VideoStyle style, String contentType) {
        applyEffects(plan, style, contentType, null);
    }

    public void applyEffects(DirectorPlan plan, VideoStyle style, String contentType,
                             AudioAnalysisResponse audioAnalysis) {
        boolean isEducational = "EDUCATIONAL".equalsIgnoreCase(contentType);
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (SceneDirection scene : plan.getScenes()) {
            EffectType lastEffect = null;

            for (Cut cut : scene.getCuts()) {
                List<EffectType> pool = resolvePool(cut, style, isEducational, audioAnalysis);
                EffectType chosen = pickFromPool(pool, lastEffect, rng);
                cut.setEffect(chosen);
                lastEffect = chosen;
            }
        }

        log.info("[EffectAssigner] Effects assigned — music-aware: {}, style: {}",
                audioAnalysis != null, style);
    }

    /**
     * Przypisuje przejścia między scenami z uwzględnieniem muzyki.
     */
    public void applyTransitions(DirectorPlan plan, VideoStyle style, String contentType) {
        applyTransitions(plan, style, contentType, null);
    }

    public void applyTransitions(DirectorPlan plan, VideoStyle style, String contentType,
                                 AudioAnalysisResponse audioAnalysis) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String lastTransition = null;

        for (int i = 0; i < plan.getScenes().size(); i++) {
            SceneDirection scene = plan.getScenes().get(i);
            boolean isLast = (i == plan.getScenes().size() - 1);

            if (isLast) {
                scene.setTransitionToNext("cut");
                continue;
            }

            // Znajdź sekcję muzyczną w momencie przejścia
            String sectionType = findSectionAtScene(scene, audioAnalysis);
            List<String> pool = resolveTransitionPool(style, sectionType);
            String chosen = pickTransition(pool, lastTransition, rng);
            scene.setTransitionToNext(chosen);
            lastTransition = chosen;
        }
    }

    // =========================================================================
    // POOL RESOLUTION
    // =========================================================================

    private List<EffectType> resolvePool(Cut cut, VideoStyle style, boolean isEducational,
                                         AudioAnalysisResponse audioAnalysis) {
        if (isEducational) return POOL_EDUCATIONAL;

        // Sekcja muzyczna ma najwyższy priorytet
        if (audioAnalysis != null) {
            String sectionType = findSectionAtMs(cut.getStartMs(), audioAnalysis);
            String sectionEnergy = findSectionEnergyAtMs(cut.getStartMs(), audioAnalysis);

            if (sectionType != null) {
                return switch (sectionType.toLowerCase()) {
                    case "drop", "peak" -> adjustForMood(POOL_AGGRESSIVE, audioAnalysis);
                    case "build", "build_up", "buildup" -> adjustForMood(POOL_BUILDING, audioAnalysis);
                    case "intro", "outro", "bridge" -> adjustForMood(POOL_CALM, audioAnalysis);
                    default -> resolveByEnergy(sectionEnergy != null ? sectionEnergy : cut.getEnergy(), style);
                };
            }
        }

        // Fallback na energy z cuta (od GPT lub beat sync)
        return resolveByEnergy(cut.getEnergy(), style);
    }

    private List<EffectType> resolveByEnergy(String energy, VideoStyle style) {
        if (style == VideoStyle.CINEMATIC || style == VideoStyle.LUXURY_AD) {
            return POOL_CINEMATIC;
        }

        if (energy == null) return POOL_BUILDING;

        return switch (energy.toLowerCase()) {
            case "high" -> POOL_AGGRESSIVE;
            case "medium" -> POOL_BUILDING;
            case "low" -> POOL_CALM;
            default -> POOL_BUILDING;
        };
    }

    /**
     * Modyfikuje pulę efektów na podstawie mood i danceability.
     * Wysoka danceability → więcej bounce/shake.
     * Sad/calm mood → łagodniejsze efekty nawet na dropie.
     */
    private List<EffectType> adjustForMood(List<EffectType> basePool, AudioAnalysisResponse audio) {
        if (audio.mood() == null) return basePool;

        String mood = audio.mood().toLowerCase();
        double dance = audio.danceability();

        // Sad/melancholic mood — nawet na dropie nie szalejemy
        if (mood.contains("sad") || mood.contains("melanchol") || mood.contains("calm")) {
            // Zamień agresywne efekty na łagodniejsze
            if (basePool == POOL_AGGRESSIVE) {
                return List.of(
                        EffectType.ZOOM_IN, EffectType.ZOOM_IN_OFFSET,
                        EffectType.PAN_UP, EffectType.PAN_DOWN, EffectType.DRIFT
                );
            }
            return basePool;
        }

        // Wysoka danceability → więcej bounce i shake
        if (dance > 0.7 && basePool == POOL_AGGRESSIVE) {
            return List.of(
                    EffectType.BOUNCE, EffectType.SHAKE, EffectType.FAST_ZOOM,
                    EffectType.BOUNCE, EffectType.ZOOM_IN_OFFSET, EffectType.SHAKE
            );
        }

        return basePool;
    }

    // =========================================================================
    // MUSIC SECTION LOOKUP
    // =========================================================================

    private String findSectionAtMs(int cutStartMs, AudioAnalysisResponse audio) {
        if (audio == null || audio.sections() == null) return null;
        double timeSec = cutStartMs / 1000.0;
        return audio.sections().stream()
                .filter(s -> timeSec >= s.start() && timeSec < s.end())
                .map(AudioAnalysisResponse.Section::type)
                .findFirst()
                .orElse(null);
    }

    private String findSectionEnergyAtMs(int cutStartMs, AudioAnalysisResponse audio) {
        if (audio == null || audio.sections() == null) return null;
        double timeSec = cutStartMs / 1000.0;
        return audio.sections().stream()
                .filter(s -> timeSec >= s.start() && timeSec < s.end())
                .map(AudioAnalysisResponse.Section::energy)
                .findFirst()
                .orElse(null);
    }

    private String findSectionAtScene(SceneDirection scene, AudioAnalysisResponse audio) {
        if (audio == null || audio.sections() == null || scene.getCuts() == null || scene.getCuts().isEmpty()) {
            return null;
        }
        // Użyj ostatniego cuta sceny (moment przejścia)
        Cut lastCut = scene.getCuts().get(scene.getCuts().size() - 1);
        return findSectionAtMs(lastCut.getEndMs(), audio);
    }

    // =========================================================================
    // TRANSITION RESOLUTION
    // =========================================================================

    private List<String> resolveTransitionPool(VideoStyle style, String sectionType) {
        // Sekcja muzyczna wpływa na przejście
        if (sectionType != null) {
            return switch (sectionType.toLowerCase()) {
                case "drop", "peak" -> TRANSITIONS_AGGRESSIVE;
                case "build", "build_up", "buildup" -> TRANSITIONS_SMOOTH;
                case "intro", "outro", "bridge" -> TRANSITIONS_SMOOTH;
                default -> TRANSITIONS_SMOOTH;
            };
        }

        // Fallback na styl
        if (style == null) return TRANSITIONS_SMOOTH;
        return switch (style) {
            case VIRAL_EDIT -> TRANSITIONS_AGGRESSIVE;
            case UGC_STYLE -> TRANSITIONS_MINIMAL;
            case CINEMATIC, LUXURY_AD -> TRANSITIONS_SMOOTH;
            default -> TRANSITIONS_SMOOTH;
        };
    }

    // =========================================================================
    // RANDOM PICK — zapobiega powtórzeniom
    // =========================================================================

    private EffectType pickFromPool(List<EffectType> pool, EffectType lastEffect, ThreadLocalRandom rng) {
        if (pool.isEmpty()) return EffectType.NONE;
        if (pool.size() == 1) return pool.get(0);

        // Próbuj uniknąć powtórzenia (max 3 próby)
        for (int attempt = 0; attempt < 3; attempt++) {
            EffectType candidate = pool.get(rng.nextInt(pool.size()));
            if (candidate != lastEffect) return candidate;
        }
        // Jeśli 3 próby nie dały innego efektu — weź jakikolwiek inny
        return pool.stream()
                .filter(e -> e != lastEffect)
                .findFirst()
                .orElse(pool.get(0));
    }

    private String pickTransition(List<String> pool, String lastTransition, ThreadLocalRandom rng) {
        if (pool.isEmpty()) return "fade";
        if (pool.size() == 1) return pool.get(0);

        for (int attempt = 0; attempt < 3; attempt++) {
            String candidate = pool.get(rng.nextInt(pool.size()));
            if (!candidate.equals(lastTransition)) return candidate;
        }
        return pool.stream()
                .filter(t -> !t.equals(lastTransition))
                .findFirst()
                .orElse(pool.get(0));
    }
}
